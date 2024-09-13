package dev.gruff.hardstop.cachegenie.utils;

import java.io.File;

public class FileChecks {

    public static  void checkFileExistsIsFileOfType(File f,String suffix) {
        if(f==null) error("file is null");
        if(suffix==null) error("no suffix type presented");

        if(!f.exists()) error("file "+f.getAbsolutePath()+" does not exist");
        suffix=suffix.trim();
        if(!suffix.startsWith(".")) suffix="."+suffix;
        suffix=suffix.toLowerCase();
        String name=f.getName().toLowerCase().trim();

        if(!name.endsWith(suffix))  error("file "+f.getAbsolutePath()+" is not of type "+suffix);

    }

    private static void error(String msg) {
        throw new RuntimeException(msg);
    }

    public static void checkFileExists(String ref, File f) {
        if(f==null) error(ref+" is null");
        if(!f.exists()) error(ref+" "+f.getAbsolutePath()+" does not exist");
    }
}
