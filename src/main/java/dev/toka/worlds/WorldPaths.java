package dev.toka.worlds;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import dev.toka.i18n.Messages;
public final class WorldPaths {
    private final Path root;
    private final Messages messages;
    public WorldPaths(Path root) throws IOException {this(root,new Messages(WorldPaths.class.getClassLoader(),root,"zh-TW",null));}
    public WorldPaths(Path root,Messages messages) throws IOException {this.root=root.toRealPath();this.messages=messages;}
    public Path root(){return root;}
    public Path world(String name) {
        if(name==null || !name.matches("[\\p{L}\\p{N}_-]{1,64}") || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) throw new IllegalArgumentException(messages.text("err.worldName"));
        Path path=root.resolve(name).normalize();
        if(!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IllegalArgumentException(messages.text("err.pathUnsafe"));
        return path;
    }
    public Path existing(String name) throws IOException {
        Path path=world(name);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)) throw new IOException(messages.text("err.worldNotFound",name));
        if(Files.isSymbolicLink(path.resolve("level.dat")) || Files.isSymbolicLink(path.resolve("db"))) throw new IOException(messages.text("err.symlink"));
        return path;
    }
    public void available(String name) throws IOException {
        Path path=world(name);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException(messages.text("err.targetExists",name));
        try(var files=Files.list(root)) {if(files.anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name))) throw new IOException(messages.text("err.caseConflict",name));}
    }
    public List<String> names() throws IOException {
        try(var files=Files.list(root)) {return files.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&Files.isRegularFile(p.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)).map(p->p.getFileName().toString()).sorted().toList();}
    }
}
