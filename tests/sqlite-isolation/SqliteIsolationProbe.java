import dev.toka.worlds.internal.storage.*;
import com.google.gson.JsonObject;
import java.nio.file.*;

public class SqliteIsolationProbe {
    public static void main(String[] args) throws Exception {
        // The parent intentionally supplies an incompatible driver: it must never be used.
        Class.forName("org.sqlite.JDBC");
        Path directory=Path.of(args[0]);
        JsonObject value=new JsonObject();value.addProperty("world", "世界測試");
        try(DocumentStore store=DocumentStore.open(new StoreConfig(directory,1))) {
            store.save("probe",value);
        }
        try(DocumentStore store=DocumentStore.open(new StoreConfig(directory,1))) {
            if(!store.load("probe").orElseThrow().equals(value))throw new AssertionError("Readback mismatch");
        }
        System.out.println("SQLITE_ISOLATION_PASS write/reopen/read with incompatible parent driver");
    }
}
