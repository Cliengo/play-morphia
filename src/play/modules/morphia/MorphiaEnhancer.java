package play.modules.morphia;

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.ArrayMemberValue;
import javassist.bytecode.annotation.EnumMemberValue;
import javassist.bytecode.annotation.MemberValue;
import org.mongodb.morphia.annotations.*;
import org.mongodb.morphia.utils.IndexDirection;
import org.osgl._;
import org.osgl.storage.KeyGenerator;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.modules.morphia.Model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This class uses the Play framework enhancement process to enhance classes
 * marked with the morphia annotations.
 *
 * @author greenlaw110@gmail.com
 */
public class MorphiaEnhancer extends Enhancer {

    public static interface FieldEnhancer {
        void enhance(CtField field, CtClass clz);
    }

    public static final String PACKAGE_NAME = "play.modules.morphia";

    private static FieldEnhancer crud = null;

    private static void enhanceCRUDField(CtField fld, CtClass clz) {
        if (MorphiaPlugin.crud) {
            if (null == crud) {
                crud = new CRUDFieldEnhancer();
            }
            crud.enhance(fld, clz);
        }
    }

    @Override
    public void enhanceThisClass(ApplicationClass applicationClass) throws Exception {
        //Logger.debug("Morphia> start to enhance class:" + applicationClass.name);
        // We won't enhance class before we get ID_type information
        enhanceThisClass_(applicationClass);
    }

    void enhanceThisClass_(ApplicationClass applicationClass) throws Exception {
        // this method will be called after configuration finished
        // if (!MorphiaPlugin.configured()) return;
        // is anonymous class?
        if (applicationClass.name.contains("$anonfun$") || applicationClass.name.contains("$anon$")) return;

        final CtClass ctClass = makeClass(applicationClass);
        final CtClass modelClass = classPool.getCtClass("play.modules.morphia.Model");
        // convert annotation (Column to Property) on fields for Embedded class
        if (!ctClass.subclassOf(modelClass) && hasAnnotation(ctClass, Embedded.class.getName())) {
            processFields(ctClass);
            applicationClass.enhancedByteCode = ctClass.toBytecode();
            ctClass.defrost();
            return;
        }

        if (!ctClass.subclassOf(modelClass)) return;
        if (hasAnnotation(ctClass, Embedded.class.getName()) ) {
            throw new Exception(String.format("Error enhancing [%s]: Embedded entity shall NOT extend play.modules.morphia.Model class!", ctClass.getName()));
        }

//        if (!hasAnnotation(ctClass, Entity.class.getName()) || hasAnnotation(ctClass, ByPass.class.getName())) {
//            // process blob fields in abstract class: issue #74
//            for (CtField cf: Arrays.asList(ctClass.getDeclaredFields())) {
//                CtClass ctReturnType = cf.getType();
//                if (ctReturnType != null && ctReturnType.getName().equals("play.modules.morphia.Blob") &&
//                        !hasAnnotation(cf, Transient.class.getName())) {
//                    throw new Exception("Cannot add Blob fields to model class without @Entity annotation");
//                }
//            }
//                // Done.
//            applicationClass.enhancedByteCode = ctClass.toBytecode();
//            ctClass.defrost();
//            return;
//        }

        boolean addId = hasAnnotation(ctClass, Entity.class.getName()); // do not add id fields to parent model without @Entity annotation

        boolean useFactory = false;

    	if (hasAnnotation(ctClass, NoId.class.getName())) {
            addId = false;
        } else {
            for (CtField cf: ctClass.getDeclaredFields()) {
                boolean hasUserDefinedId = hasAnnotation(cf, Id.class.getName());
                String fName = cf.getName();
                boolean is_id = "_id".equals(fName);
                if (hasUserDefinedId && !is_id) useFactory = true;
                if (hasUserDefinedId || is_id) {
                    addId = false;
                    break;
                }
            }
            for (CtField cf: ctClass.getFields()) {
                boolean hasUserDefinedId = hasAnnotation(cf, Id.class.getName());
                String fName = cf.getName();
                boolean is_id = "_id".equals(fName);
                if (hasUserDefinedId && !is_id) useFactory = true;
                if (hasUserDefinedId || is_id) {
                    addId = false;
                    break;
                }
            }
        }

        boolean autoTS = MorphiaPlugin.autoTS_ && hasAnnotation(ctClass, Entity.class.getName());
        if (hasAnnotation(ctClass, play.modules.morphia.Model.NoAutoTimestamp.class.getName())) autoTS = false;
        else if (hasAnnotation(ctClass, play.modules.morphia.Model.AutoTimestamp.class.getName())) autoTS = true;
        for (CtField cf: ctClass.getDeclaredFields()) {
            if (cf.getName().equals("_created")) {
                autoTS = false;
            }
        }
        for (CtField cf: ctClass.getFields()) {
            if (cf.getName().equals("_created")) {
                autoTS = false;
            }
        }

        enhance_(ctClass, applicationClass, addId, useFactory, autoTS);
    }

    protected <T> T getAnnotation(CtClass ctClass, String annotation) throws ClassNotFoundException {
        for (Object object : ctClass.getAvailableAnnotations()) {
            java.lang.annotation.Annotation ann = (java.lang.annotation.Annotation) object;
            if (ann.annotationType().getName().equals(annotation)) {
                return (T)ann;
            }
        }
        return null;
    }
    
    protected <T> T getAnnotation(CtMember ctMember, String annotation) throws ClassNotFoundException {
        for (Object object : ctMember.getAvailableAnnotations()) {
            java.lang.annotation.Annotation ann = (java.lang.annotation.Annotation) object;
            if (ann.annotationType().getName().equals(annotation)) {
                return (T)ann;
            }
        }
        return null;
    }

    /**
     * Enhance classes marked with the MongoEntity annotation.
     *
     * @param ctClass
     * @throws Exception
     */
    private void enhance_(CtClass ctClass, ApplicationClass applicationClass, boolean addId, boolean useFactory, boolean autoTS) throws Exception {
        String entityName = ctClass.getName();
        play.modules.morphia.MorphiaPlugin.debug("enhancing %s ...", entityName);

        // Don't need to fully qualify types when compiling methods below
        classPool.importPackage(PACKAGE_NAME);

        String className = entityName + ".class";

        // ModalFactory
        // create an id field
        // CtField mf = new CtField(classPool.get(play.db.Model.Factory.class.getName()), "mf", ctClass);
        CtField mf = CtField.make("protected static play.db.Model.Factory mf = MorphiaPlugin.MorphiaModelLoader.getFactory(" + className + ");", ctClass);
        ctClass.addField(mf);
        // getModelFactory
        CtMethod getModelFactory = CtMethod.make("public static play.db.Model.Factory getModelFactory() { return mf; }",ctClass);
        ctClass.addMethod(getModelFactory);

        // id field
        if (addId) {
            Logger.trace("Adding id methods to system managed ID entity: %1$s", ctClass.getName());
            // create an id field
            Logger.trace("type name: %1$s", play.modules.morphia.utils.IdGenerator.getIdTypeName());
            String idType = play.modules.morphia.utils.IdGenerator.getIdTypeName();
            CtField idField = new CtField(classPool.get(idType), "_id", ctClass);
            idField.setModifiers(Modifier.PUBLIC);
            AnnotationsAttribute aa = new AnnotationsAttribute(ctClass.getClassFile().getConstPool(),
                    AnnotationsAttribute.visibleTag);
            Annotation idAnn = new Annotation(Id.class.getName(), ctClass.getClassFile().getConstPool());
            aa.addAnnotation(idAnn);
            enhanceCRUDField(idField, ctClass);
            idField.getFieldInfo().addAttribute(aa);
            ctClass.addField(idField);
            Logger.trace("ID field added to entity[%2$s]: %1$s", idField.getSignature(), ctClass.getName());
            // id()
            CtMethod getId = CtMethod.make("public Object getId() { return _id;}", ctClass);
            ctClass.addMethod(getId);
            // setId
            CtMethod setId = CtMethod.make("protected void setId_(Object id) { _id = (" + idType + ")play.modules.morphia.utils.IdGenerator.processId(id);}", ctClass);
            ctClass.addMethod(setId);
        } else {
            Logger.trace("adding id methods to user defined id entity: %1$s", ctClass.getName());
            // a general id() method for user marked Id field
            boolean hasGetId = false;
            for (CtMethod cm: ctClass.getDeclaredMethods()) {
                if ("getId".equals(cm.getName()) && cm.getDeclaringClass().equals(ctClass)) {
                    // user has defined getId already
                    hasGetId = true;
                    break;
                }
            }
            if (!hasGetId) {
                CtMethod getId = CtMethod.make("public Object getId() { return mf.keyValue(this);}", ctClass);
                ctClass.addMethod(getId);
            }
            // setId - for user marked Id entity, setId method needs to be override

            CtMethod isUserDefinedId = CtMethod.make("protected boolean isUserDefinedId_() {return super.isUserDefinedId_();}", ctClass);
            ctClass.addMethod(isUserDefinedId);
        }

        // create timestamp?
        if (autoTS) {
            ClassFile classFile = ctClass.getClassFile();
            ConstPool cp = classFile.getConstPool();
            AnnotationsAttribute attribute = new AnnotationsAttribute(cp, AnnotationsAttribute.visibleTag);
            Annotation indexAnnotation = new Annotation("org.mongodb.morphia.annotations.Indexed", cp); //https://stackoverflow.com/questions/6999365/unexpected-nullpointerexception-using-javassist
            EnumMemberValue val = new EnumMemberValue(cp);
            val.setType(IndexDirection.class.getName());
            val.setValue(IndexDirection.DESC.name());
            indexAnnotation.addMemberValue("value", val);
            attribute.addAnnotation(indexAnnotation);

        	Logger.trace("create timestamp fields automatically");
        	CtField createdField = new CtField(CtClass.longType, "_created", ctClass);
        	createdField.getFieldInfo().addAttribute(attribute);
        	createdField.setModifiers(Modifier.PUBLIC);
        	enhanceCRUDField(createdField, ctClass);
        	ctClass.addField(createdField);

        	CtField modifiedField = new CtField(CtClass.longType, "_modified", ctClass);
        	modifiedField.getFieldInfo().addAttribute(attribute);
        	modifiedField.setModifiers(Modifier.PUBLIC);
            enhanceCRUDField(modifiedField, ctClass);
        	ctClass.addField(modifiedField);

        	CtMethod persistTs = CtMethod.make("void _updateTimestamp() { long now = System.currentTimeMillis(); if (0 == _created) {_created = now;} ;_modified = now;}", ctClass);
            AnnotationsAttribute aa = new AnnotationsAttribute(ctClass.getClassFile().getConstPool(),
                    AnnotationsAttribute.visibleTag);
            Annotation prePersistAnn = new Annotation(PrePersist.class.getName(), ctClass.getClassFile().getConstPool());
            aa.addAnnotation(prePersistAnn);
            persistTs.getMethodInfo().addAttribute(aa);
            ctClass.addMethod(persistTs);

            CtMethod getCreated = CtMethod.make("public long _getCreated() { return _created; }", ctClass);
            ctClass.addMethod(getCreated);

            CtMethod getModified = CtMethod.make("public long _getModified() { return _modified; }", ctClass);
            ctClass.addMethod(getModified);
        }

        // updateOperations
        CtMethod createUpdateOperations = CtMethod.make("public static play.modules.morphia.Model.MorphiaUpdateOperations createUpdateOperations() { return o(); }",ctClass);
        ctClass.addMethod(createUpdateOperations);

        // updateOperations
        CtMethod o = CtMethod.make("public static play.modules.morphia.Model.MorphiaUpdateOperations o() { return new play.modules.morphia.Model.MorphiaUpdateOperations("+ className + "); }",ctClass);
        ctClass.addMethod(o);

        // all - alias of find()
        CtMethod all = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery all() { return new play.modules.morphia.Model.MorphiaQuery("+ className + "); }",ctClass);
        ctClass.addMethod(all);

        // create
        CtMethod create = CtMethod.make("public static play.modules.morphia.Model create(String name, play.mvc.Scope.Params params) { Object o = play.Play.classloader.loadClass(\""+ entityName + "\").newInstance(); return ((play.modules.morphia.Model)o).edit(name, params.all()); }",ctClass);
        ctClass.addMethod(create);

        // createQuery - alias of all
        CtMethod createQuery = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery createQuery() { return all(); }",ctClass);
        ctClass.addMethod(createQuery);

        // q - alias of createQuery
        CtMethod q = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery q() { return all(); }",ctClass);
        ctClass.addMethod(q);

        // disableValidation
        CtMethod disableValidation = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery disableValidation() { return all().disableValidation(); }",ctClass);
        ctClass.addMethod(disableValidation);

        // find -- alias: all()
        CtMethod find = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery find() { return all(); }",ctClass);
        ctClass.addMethod(find);

        // find(String keys, Object... params)
        CtMethod find2 = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery find(String keys, java.lang.Object[] params) { return createQuery().findBy(keys, params); }",ctClass);
        ctClass.addMethod(find2);

        // q -- alias: filter(String, Object...)
        CtMethod q2 =  CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery q(String keys, java.lang.Object value) { return q().filter(keys, value); }",ctClass);
        ctClass.addMethod(q2);

        // findAll
        CtMethod findAll = CtMethod.make("public static java.util.List findAll() {return all().asList();}", ctClass);
        ctClass.addMethod(findAll);

        // filter(property, value)
        CtMethod filter = CtMethod.make("public static play.modules.morphia.Model.MorphiaQuery filter(String property, Object value) { return find().filter(property, value); }",ctClass);
        ctClass.addMethod(filter);

        // get()
        CtMethod get = CtMethod.make("public static Model get() { return find().get(); }",ctClass);
        ctClass.addMethod(get);

        // cache methods
        boolean useCache = hasAnnotation(ctClass, CacheEntity.class.getName());
        CacheEntity cacheEntity = useCache ? (CacheEntity)getAnnotation(ctClass, CacheEntity.class.getName()) /*ctClass.getAnnotation(CacheEntity.class)*/ : null;
        String expiration = null == cacheEntity ? "" : cacheEntity.value();
        CtMethod _cacheEnabled = CtMethod.make("protected boolean _cacheEnabled() { return " + useCache + "; }", ctClass);
        ctClass.addMethod(_cacheEnabled);
        CtMethod _cacheExpiration = CtMethod.make("protected String _cacheExpiration() { return \"" + expiration + "\";}" , ctClass);
        ctClass.addMethod(_cacheExpiration);

        // findById
        CtMethod findById = CtMethod.make("public static Model findById(java.lang.Object id) {return findById(" + className + ", id, " + useFactory + ", " + useCache + ", \"" + expiration + "\");}", ctClass);
        ctClass.addMethod(findById);


        // col
        CtMethod col = CtMethod.make("public static com.mongodb.DBCollection col() { return ds().getCollection(" + className + "); }", ctClass);
        ctClass.addMethod(col);

        // count
        CtMethod count = CtMethod.make("public static long count() { return ds().getCount(" + className + "); }", ctClass);
        ctClass.addMethod(count);

        // count (String keys, Object... params)
        CtMethod count2 = CtMethod.make("public static long count(String keys, Object[] params) { return find(keys, params).count(); }", ctClass);
        ctClass.addMethod(count2);

        // distinct
        CtMethod distinct = CtMethod.make(String.format("public static java.util.Set _distinct(String key) {return q().distinct(key);}", className), ctClass);
        ctClass.addMethod(distinct);

        // cloud
        CtMethod cloud = CtMethod.make("public static java.util.Map _cloud(String key) {return q().cloud(key);}", ctClass);
        ctClass.addMethod(cloud);

        // max
        CtMethod max = CtMethod.make("public static Long _max(String field) {return q().max(field);}", ctClass);
        ctClass.addMethod(max);

        // group-max
        CtMethod groupmax = CtMethod.make("public static AggregationResult groupMax(String field, String[] groupKeys) {return q().groupMax(field, groupKeys);}", ctClass);
        ctClass.addMethod(groupmax);

        // min
        CtMethod min = CtMethod.make("public static Long _min(String field) {return q().min(field);}", ctClass);
        ctClass.addMethod(min);

        // group-min
        CtMethod groupMin = CtMethod.make("public static AggregationResult groupMin(String field, String[] groupKeys) {return q().groupMin(field, groupKeys);}", ctClass);
        ctClass.addMethod(groupMin);

        // average
        CtMethod average = CtMethod.make("public static Long _average(String field) {return q().average(field);}", ctClass);
        ctClass.addMethod(average);

        // group-average
        CtMethod groupAverage = CtMethod.make("public static AggregationResult groupAverage(String field, String[] groupKeys) {return q().groupAverage(field, groupKeys);}", ctClass);
        ctClass.addMethod(groupAverage);

        // sum
        CtMethod sum = CtMethod.make("public static Long _sum(String field) {return q().sum(field);}", ctClass);
        ctClass.addMethod(sum);

        // group-sum
        CtMethod groupSum = CtMethod.make("public static AggregationResult groupSum(String field, String[] groupKeys) {return q().groupSum(field, groupKeys);}", ctClass);
        ctClass.addMethod(groupSum);

        // group-count
        CtMethod groupCount = CtMethod.make("public static AggregationResult groupCount(String[] groupKeys) {return q().groupCount(groupKeys);}", ctClass);
        ctClass.addMethod(groupCount);

        // deleteAll
        CtMethod deleteAll = CtMethod.make("public static long deleteAll() { return delete(all()); }",ctClass);
        ctClass.addMethod(deleteAll);

        // add @Transient to all blobs automatically
        Map<String, _.T2<KeyGenerator, String>> blobs = processFields(ctClass);
        boolean hasBlobField = blobs.size() > 0;

        // enhance blob methods: save, delete, batchDelete, load and setters
        if (hasBlobField) enhanceBlobMethods(ctClass, blobs);

        // add lifecycle handling code
        addLifeCycleMethods(ctClass);

        // Done.
        applicationClass.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }

    private void enhanceBlobMethods(CtClass ctClass, Map<String, _.T2<KeyGenerator, String>> blobs) throws CannotCompileException, NotFoundException {
        // -- get blob storage service
        StringBuilder sb = S.builder("protected play.modules.morphia.BlobStorageService bss(String field) {");
        for (String blob : blobs.keySet()) {
            _.T2<KeyGenerator, String> anno = blobs.get(blob);
            sb.append(S.fmt("\nif (\"%s\".equals(field)) {\n\treturn play.modules.morphia.MorphiaPlugin.bss(%s, \"%s\");\n}", blob, KeyGenerator.class.getName() + "." + anno._1.name(), anno._2));
        }
        sb.append("\nthrow new java.lang.IllegalArgumentException(\"unknown blob field: \" + field);\n}");
        
        CtMethod method = CtMethod.make(sb.toString(), ctClass);
        ctClass.addMethod(method);

        // -- saveBlobs
        sb = S.builder("protected void saveBlobs() {");
        for (String blob : blobs.keySet()) {
            sb.append(String.format("\n\t{\n\t\tBlob blob = %1$s; \n\t\tif (null != blob && __blobChanged(\"%1$s\")) {\n\t\t\tString key = blob.createKey(getIdAsStr(), \"%1$s\", bss(\"%1$s\")); \n\t\t\tblob.save(key, bss(\"%1$s\")); \n\t\t\t__setBlobKey(\"%1$s\", key);\n\t\t}\n\t}", blob));
        }
        sb.append("\n\tblobFieldsTracker.clear();}");
        method = CtMethod.make(sb.toString(), ctClass);
        ctClass.addMethod(method);

        // -- loadBlobs
        sb = new StringBuilder("protected boolean loadBlobs() {");
        sb.append("\n\tboolean needsave = false;");
        for (String blob : blobs.keySet()) {
            sb.append(String.format("\n\t{\n\t\t%1$s = %2$s.load((String)%3$s.ensureGet(__getBlobKey(\"%1$s\"), getBlobFileName(\"%1$s\")), bss(\"%1$s\"));\n\t\tif (null == __getBlobKey(\"%1$s\") && null != %1$s) {__setBlobKey(\"%1$s\", %1$s.getKey());save();needsave = true;}\n\t}", blob, Blob.class.getName(), _.class.getName()));
        }
        sb.append("\n\tblobFieldsTracker.clear();\nreturn needsave;}");
        method = CtMethod.make(sb.toString(), ctClass);
        ctClass.addMethod(method);

        // -- blob setters
        for (String blob : blobs.keySet()) {
            String setter = "set" + S.capFirst(blob);
            CtMethod ctMethod = ctClass.getDeclaredMethod(setter);
            ctMethod.insertAfter(String.format("__setBlobChanged(\"%s\");", blob));
        }
    }

    /*
     * 1. Add @Transparent to all Blob field
     * 2. Convert @play.modules.morphia.Model.Column to @org.mongodb.morphia.annotations.Property
     * 3. Convert @play.data.validation.Unique to @play.modules.morphia.validation.Unique
     * 3. Return a list of names of Blob fields
     */
    private Map<String, _.T2<KeyGenerator, String>> processFields(CtClass ctClass) throws NotFoundException, ClassNotFoundException {
        List<CtField> fields  = new ArrayList<CtField>();
        fields.addAll(Arrays.asList(ctClass.getDeclaredFields()));
        fields.addAll(Arrays.asList(ctClass.getFields()));
        Map<String, _.T2<KeyGenerator, String>> blobs = C.newMap();
        List<MemberValue> converterList = new ArrayList<MemberValue>();
        for (CtField cf: fields) {
            CtClass ctReturnType = cf.getType();
            if (ctReturnType != null && ctReturnType.getName().equals("play.modules.morphia.Blob") && cf.getDeclaringClass().getName().equals(ctClass.getName())) {
                createAnnotation(getAnnotations(cf), Transient.class);
                KeyGenerator kg = KeyGenerator.BY_DATE;
                BlobStorageService.KeyGen annKG = getAnnotation(cf, BlobStorageService.KeyGen.class.getName());
                if (null != annKG) {
                    kg = annKG.value(); 
                }
                String ss = "";
                BlobStorageService.Storage annSS = getAnnotation(cf, BlobStorageService.Storage.class.getName());
                if (null != annSS) {
                    String storage = annSS.value();
                    if (S.notEmpty(storage)) {
                        Class ssCls = MorphiaPlugin.getStorageClass(storage);
                        if (null == ssCls) {
                            throw E.invalidConfiguration("storage[%s] not configured", storage);
                        }
                        ss = storage;
                    }
                }
                blobs.put(cf.getName(), _.T2(kg, ss));
                continue;
            }

            if (Modifier.isTransient(cf.getModifiers())) {
                createAnnotation(getAnnotations(cf), Transient.class);
                continue;
            }

            if (Modifier.isStatic(cf.getModifiers())) continue;

            AnnotationsAttribute attr = getAnnotations(cf);
            Annotation[] aa = attr.getAnnotations();
            Annotation colA = null;
            Annotation propA = null;
            Annotation uniquePlay = null;
            Annotation uniqueMorhpia = null;
            for (Annotation a: aa) {
                if (a.getTypeName().equals(Column.class.getName())) {
                    colA = a;
                } else if (a.getTypeName().equals(Property.class.getName())) {
                    propA = a;
                } else if (a.getTypeName().equals(play.modules.morphia.validation.Unique.class.getName())) {
                    uniqueMorhpia = a;
                } else if (a.getTypeName().equals(play.data.validation.Unique.class.getName())) {
                    uniquePlay = a;
                }
                
            }
            while (true) {
                // check if there are converters annotation added to the field type declaration
                CtClass fieldClass = cf.getType();
                String fieldClassName = fieldClass.getName();
                if (fieldClass.isPrimitive() || fieldClass.isArray()) break;
                if (fieldClass.isFrozen()) fieldClass.defrost();
                AnnotationsAttribute attrC = getAnnotations(fieldClass);
                Annotation a = attrC.getAnnotation(Converters.class.getName());
                if (null != a) {
                    MemberValue value = a.getMemberValue("value");
                    if (null == value) continue;
                    ArrayMemberValue av = (ArrayMemberValue)value;
                    MemberValue[] va = av.getValue();
                    converterList.addAll(Arrays.asList(va));
                }
                break;
            }
            if (null == propA && null != colA) {
                MemberValue value = colA.getMemberValue("value");
                MemberValue concreteClass = colA.getMemberValue("concreteClass");
                if (null == value && null == concreteClass) continue;
                propA = new Annotation(Property.class.getName(), ctClass.getClassFile().getConstPool());
                if (null != value) propA.addMemberValue("value", value);
                if (null != concreteClass) propA.addMemberValue("concreteClass", concreteClass);
                attr.addAnnotation(propA);
            }
            if (null == uniqueMorhpia && null != uniquePlay) {
                MemberValue value = uniquePlay.getMemberValue("value");
                MemberValue message = uniquePlay.getMemberValue("message");
                uniqueMorhpia = new Annotation(play.modules.morphia.validation.Unique.class.getName(), ctClass.getClassFile().getConstPool());
                if (null != value) uniqueMorhpia.addMemberValue("value", value);
                if (null != message) uniqueMorhpia.addMemberValue("message", message);
                attr.addAnnotation(uniqueMorhpia);
            }
            if (null != uniquePlay) {
                Annotation[] anns = attr.getAnnotations();
                List<Annotation> newAnns = new ArrayList<Annotation>(anns.length - 1);
                for (Annotation ann: anns) {
                    if (!ann.getTypeName().equals(play.data.validation.Unique.class.getName())) {
                        newAnns.add(ann);
                    }
                }
                attr.setAnnotations(newAnns.toArray(new Annotation[]{}));
            }
            cf.getFieldInfo().addAttribute(attr);
        }
        if (!converterList.isEmpty()) {
            AnnotationsAttribute clsAttr = getAnnotations(ctClass);
            Annotation converters = clsAttr.getAnnotation(Converters.class.getName());
            if (null == converters) {
                converters = new Annotation(Converters.class.getName(), ctClass.getClassFile().getConstPool());
            } else {
                converterList.addAll(Arrays.asList(((ArrayMemberValue)converters.getMemberValue("value")).getValue()));
            }
            ArrayMemberValue amv = new ArrayMemberValue(ctClass.getClassFile().getConstPool());
            amv.setValue(converterList.toArray(new MemberValue[0]));
            converters.addMemberValue("value", amv);
            clsAttr.addAnnotation(converters);
            ctClass.getClassFile().addAttribute(clsAttr);
        }

        return blobs;
    }

    private void addLifeCycleMethods(CtClass ctClass) throws Exception {
        /* loop through all non-private methods including inherited one */
        for (CtMethod cm: ctClass.getMethods()) {
            enhanceLifeCycleMethods(ctClass, cm);
            enhanceLifeCycleBatchMethods(ctClass, cm);
        }
        /* loop through all private methods declared */
        for (CtMethod cm: ctClass.getDeclaredMethods()) {
            if (Modifier.isPrivate(cm.getModifiers())) {
                enhanceLifeCycleMethods(ctClass, cm);
                enhanceLifeCycleBatchMethods(ctClass, cm);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void enhanceLifeCycleMethods(CtClass ctClass, CtMethod ctMethod) throws Exception {
        if (ctMethod.getParameterTypes().length > 0) return; // lifecycle method shall not have parameter
        if (ctMethod.getAnnotations().length == 0) return; // not annotated
        if (!"void".equals(ctMethod.getReturnType().getName())) return; // lifecycle method shall not return any object

        Class[] ca = {OnLoad.class, Loaded.class, OnAdd.class, OnUpdate.class, Added.class, Updated.class, OnDelete.class, Deleted.class, OnBatchDelete.class, BatchDeleted.class};
        AnnotationsAttribute aa = getAnnotations(ctMethod);
        for (Annotation a: aa.getAnnotations()) {
            for (Class c0: ca) {
                String nm = c0.getName();
                if (nm.equals(a.getTypeName())) {
                    // the name of lifecycle event handler, could be h_OnAdd etc.
                    String mn = "h_" + c0.getSimpleName();
                    CtMethod m0 = null;
                    try {
                        m0 = ctClass.getDeclaredMethod(mn);
                    } catch (Exception e) {
                        m0 = CtMethod.make(String.format("protected void %s(){}", mn, mn), ctClass);
                        ctClass.addMethod(m0);
                    }

                    String callback = ctMethod.getName();
                    Logger.trace("Adding callback[%s] to lifecycle event handler[%s]", callback, mn);
                    m0.insertAfter(String.format("%s();", callback));
                }
            }
        }

    }

    @SuppressWarnings("rawtypes")
    private void enhanceLifeCycleBatchMethods(CtClass ctClass, CtMethod ctMethod) throws Exception {
        if (ctMethod.getAnnotations().length == 0) return; // not annotated
        if (!"void".equals(ctMethod.getReturnType().getName())) return; // lifecycle method shall not return any object
        CtClass[] params = ctMethod.getParameterTypes();
        if (params.length == 1) {
            CtClass p0 = params[0];
            if (!MorphiaQuery.class.getName().equals(p0.getName())) return;
        } else {
            // batch lifecycle methods shall accept one parameter: MorphhiaQuery
            return;
        }

        Class[] ca = {OnBatchDelete.class, BatchDeleted.class};
        AnnotationsAttribute aa = getAnnotations(ctMethod);
        for (Annotation a: aa.getAnnotations()) {
            for (Class c0: ca) {
                String nm = c0.getName();
                if (nm.equals(a.getTypeName())) {
                    // the name of lifecycle event handler, could be h_OnAdd etc.
                    String mn = "h_" + c0.getSimpleName();
                    CtMethod m0 = null;
                    try {
                        m0 = ctClass.getDeclaredMethod(mn);
                    } catch (Exception e) {
                        m0 = CtMethod.make(String.format("protected void %s(play.modules.morphia.Model.MorphiaQuery q){}", mn), ctClass);
                        ctClass.addMethod(m0);
                    }

                    String callback = ctMethod.getName();
                    Logger.trace("Adding callback[%s] to lifecycle event handler[%s]", callback, mn);
                    m0.insertAfter(String.format("%s($$);", callback));
                }
            }
        }
    }

//    private boolean isSynthetic(CtMethod method) {
//        return (method.getMethodInfo().getAccessFlags() & AccessFlag.SYNTHETIC) != 0;
//    }
}
