package dev.gruff.hardstop.cachegenie.actions.index;

import org.apache.maven.index.reader.WritableResourceHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Local directory-backed {@link WritableResourceHandler}. Used as the "local" side
 * of {@link org.apache.maven.index.reader.IndexReader} to persist the index
 * {@code .properties} between runs, which is what enables incremental updates.
 * Single-threaded use.
 */
public final class FileWritableResourceHandler implements WritableResourceHandler {

    private final File dir;

    public FileWritableResourceHandler(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @Override
    public WritableResource locate(String name) {
        File file = new File(dir, name);
        return new WritableResource() {
            @Override
            public InputStream read() throws IOException {
                return file.isFile() ? new FileInputStream(file) : null;
            }

            @Override
            public OutputStream write() throws IOException {
                return new FileOutputStream(file);
            }
        };
    }
}
