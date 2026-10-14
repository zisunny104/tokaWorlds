package ru.nukkit.dblib;
import cn.nukkit.plugin.PluginBase;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
/** Test-only in-memory fixture. Never deploy this as the real DbLib plugin. */
public class DbLib extends PluginBase {
    private static ConnectionSource source;
    public static ConnectionSource getConnectionSource() { return source; }
    public void onEnable() {
        try { source=new JdbcConnectionSource("jdbc:sqlite::memory:"); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void onDisable() {
        try { if(source!=null)source.close(); }catch(Exception e) { throw new IllegalStateException(e); }
    }
}
