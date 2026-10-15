package org.sqlite;
public class JDBC implements java.sql.Driver {
    public java.sql.Connection connect(String url,java.util.Properties properties) {
        throw new NoSuchMethodError("Simulated incompatible parent SQLite driver");
    }
    public boolean acceptsURL(String url){return true;}
    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u,java.util.Properties p){return new java.sql.DriverPropertyInfo[0];}
    public int getMajorVersion(){return 0;}
    public int getMinorVersion(){return 0;}
    public boolean jdbcCompliant(){return false;}
    public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
}
