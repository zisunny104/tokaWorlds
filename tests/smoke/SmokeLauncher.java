import java.io.*;
public class SmokeLauncher {
    private static PipedOutputStream writer;
    public static void main(String[] args) throws Exception {
        writer=new PipedOutputStream();System.setIn(new PipedInputStream(writer));
        cn.nukkit.Nukkit.main(args);
    }
}
