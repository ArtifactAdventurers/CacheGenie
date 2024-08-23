package dev.gruff.hardstop.treestreamer.streamers;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Stream;

public abstract sealed class FileSystemTreeStreamer implements TreeStreamer<File> permits  FileSystemTreeStreamer.InternalFileSystemTreeStreamer {
   private File root;
   private boolean suppressDirs=false;
    public FileSystemTreeStreamer(File froot) {
        this.root=froot;
    }

    @Override
    public Stream<File> stream() {
        return expand(root);
    }

    private Stream<File> expand(File r) {

        if(r==null) return Stream.empty();
        File[] kids=r.listFiles();
        if(kids==null || kids.length==0) return Stream.of(r);
        if(suppressDirs) return Arrays.stream(kids).flatMap(this::expand);
        return Stream.concat(Stream.of(r),Arrays.stream(kids).flatMap(this::expand));


    }

    public static Config builder(File root) {
        if (root == null) throw new RuntimeException("missing tree root");

        return new Config(root);
    }
    public static final class Config {

        private File root = null;
        private boolean suppressDirectories=false;

        private Config(File root) {
            this.root=root;
        }

        public FileSystemTreeStreamer build() {



            FileSystemTreeStreamer fs= new InternalFileSystemTreeStreamer(root);
            if(suppressDirectories) fs.suppressDirectories();

            return fs;
        }

        public Config suppressDirectories(boolean b) {
            this.suppressDirectories=b;
            return this;
        }
    }

    private void suppressDirectories() {

            suppressDirs=true;
    }

    public static final class InternalFileSystemTreeStreamer extends FileSystemTreeStreamer {


            private InternalFileSystemTreeStreamer(File froot) {
                super(froot);
            }
        }

}
