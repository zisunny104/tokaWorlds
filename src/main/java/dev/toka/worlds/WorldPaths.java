package dev.toka.worlds;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
public final class WorldPaths {
    private final Path root;
    public WorldPaths(Path root) throws IOException {this.root=root.toRealPath();}
    public Path root(){return root;}
    public Path world(String name) {
        if(name==null || !name.matches("[\\p{L}\\p{N}_-]{1,64}") || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) throw new IllegalArgumentException("世界名稱只能包含文字、數字、底線與連字號（1～64 字）。");
        Path path=root.resolve(name).normalize();
        if(!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("世界路徑不安全");
        return path;
    }
    public Path existing(String name) throws IOException {
        Path path=world(name);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)) throw new IOException("找不到完整世界："+name);
        if(Files.isSymbolicLink(path.resolve("level.dat")) || Files.isSymbolicLink(path.resolve("db"))) throw new IOException("不接受世界資料的符號連結");
        return path;
    }
    public void available(String name) throws IOException {
        Path path=world(name);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("目的世界已存在，未覆蓋："+name);
        try(var files=Files.list(root)) {if(files.anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name))) throw new IOException("目的名稱大小寫衝突："+name);}
    }
    public List<String> names() throws IOException {
        try(var files=Files.list(root)) {return files.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&Files.isRegularFile(p.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)).map(p->p.getFileName().toString()).sorted().toList();}
    }
}
