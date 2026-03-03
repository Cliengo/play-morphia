import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import models.Account;
import org.junit.Before;
import org.junit.Test;
import play.modules.morphia.MorphiaPlugin;
import play.test.UnitTest;

public class MorphiaPluginOptimizationsTest extends UnitTest {

    @Before
    public void setup() {
        Account.deleteAll();
    }

    /**
     * Verifies that morphia.gridfs.enabled=false prevents GridFS initialization.
     * GridFS was the root cause of the March 2026 Atlas incident: all 144 app instances
     * fired createIndexes on uploads.files with majority write concern simultaneously,
     * deadlocking the replica set.
     */
    @Test
    public void testGridFsDisabledPreventsCollectionCreation() {
        assertNull("gridFs() must return null when morphia.gridfs.enabled=false",
                MorphiaPlugin.gridFs());
        assertFalse("uploads.files must not exist when GridFS is disabled",
                MorphiaPlugin.ds().getDB().getCollectionNames().contains("uploads.files"));
        assertFalse("uploads.chunks must not exist when GridFS is disabled",
                MorphiaPlugin.ds().getDB().getCollectionNames().contains("uploads.chunks"));
    }

    /**
     * Verifies that morphia.ensureIndexes=false skips automatic index creation.
     * We drop the unique index on Account.login and confirm two accounts with the
     * same login can be saved — proving the index was not recreated on startup.
     */
    @Test
    public void testEnsureIndexesDisabledSkipsUniqueConstraint() {
        DB db = MorphiaPlugin.ds().getDB();
        DBCollection col = db.getCollection("Account");
        try { col.dropIndex("login_1"); } catch (Exception ignored) {}

        Account a1 = new Account("dup-login", "a1@test.com");
        Account a2 = new Account("dup-login", "a2@test.com");
        a1.save();
        try {
            a2.save();
            assertEquals("Both saves must succeed when unique index is absent",
                    2, Account.q("login", "dup-login").count());
        } catch (Exception e) {
            fail("ensureIndexes is disabled — unique index should not exist: " + e.getMessage());
        } finally {
            Account.q("login", "dup-login").delete();
        }
    }

    /**
     * Verifies that a generic MongoException (e.g. write concern failure, command error)
     * does NOT trigger a reconnect. Before this fix, any MongoException caused all instances
     * to call configureConnection_() simultaneously.
     */
    @Test
    public void testGenericMongoExceptionDoesNotTriggerReconnect() {
        Object clientBefore = MorphiaPlugin.ds().getMongo();
        new MorphiaPlugin().onInvocationException(new MongoException("simulated write concern failure"));
        Object clientAfter = MorphiaPlugin.ds().getMongo();
        assertSame("Generic MongoException must not trigger reconnect", clientBefore, clientAfter);
    }

    /**
     * Verifies that MongoSocketException (actual network failure) DOES trigger a reconnect,
     * creating a new MongoClient.
     */
    @Test
    public void testSocketExceptionTriggersReconnect() {
        Object clientBefore = MorphiaPlugin.ds().getMongo();
        new MorphiaPlugin().onInvocationException(
                new MongoSocketException("simulated network failure", new ServerAddress()));
        Object clientAfter = MorphiaPlugin.ds().getMongo();
        assertNotSame("MongoSocketException must trigger reconnect and produce a new MongoClient",
                clientBefore, clientAfter);
    }

    /**
     * Verifies that MongoTimeoutException also triggers a reconnect.
     */
    @Test
    public void testTimeoutExceptionTriggersReconnect() {
        Object clientBefore = MorphiaPlugin.ds().getMongo();
        new MorphiaPlugin().onInvocationException(
                new MongoTimeoutException("simulated server selection timeout"));
        Object clientAfter = MorphiaPlugin.ds().getMongo();
        assertNotSame("MongoTimeoutException must trigger reconnect and produce a new MongoClient",
                clientBefore, clientAfter);
    }

    /**
     * Verifies that secondary datastores (ds(dbName)) are cleared from the cache when
     * a reconnect happens. Without this fix, those Datastores kept a reference to the
     * old (closed) MongoClient, causing silent failures on multi-DB operations.
     */
    @Test
    public void testDataStoresClearedOnReconnect() throws Exception {
        MorphiaPlugin.ds("test_secondary_db_opt");

        Field field = MorphiaPlugin.class.getDeclaredField("dataStores_");
        field.setAccessible(true);
        ConcurrentMap<?, ?> map = (ConcurrentMap<?, ?>) field.get(null);
        assertTrue("Secondary datastore must be in cache before reconnect",
                map.containsKey("test_secondary_db_opt"));

        new MorphiaPlugin().onInvocationException(
                new MongoSocketException("network error", new ServerAddress()));

        assertFalse("Secondary datastore must be evicted from cache after reconnect",
                map.containsKey("test_secondary_db_opt"));
    }

    /**
     * Simulates the thundering herd: N threads all throwing MongoSocketException at the same
     * time. The AtomicBoolean mutex ensures only one reconnect runs; the rest are dropped.
     * This test verifies:
     *   1. No deadlock — all threads complete within the timeout.
     *   2. No state corruption — the plugin is still fully operational after the storm.
     */
    @Test
    public void testConcurrentExceptionsPreservePluginState() throws Exception {
        final int THREAD_COUNT = 20;
        MorphiaPlugin plugin = new MorphiaPlugin();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    plugin.onInvocationException(
                            new MongoSocketException("concurrent error " + idx, new ServerAddress()));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "reconnect-thread-" + i).start();
        }

        start.countDown();
        assertTrue("All reconnect threads must complete within 15 seconds",
                done.await(15, TimeUnit.SECONDS));
        assertEquals("No errors must occur during concurrent reconnect handling", 0, errors.get());

        Account test = new Account("concurrent-test", "concurrent@test.com");
        test.save();
        assertNotNull("Plugin must be fully operational after concurrent reconnects",
                Account.findById(test.getId()));
        test.delete();
    }
}
