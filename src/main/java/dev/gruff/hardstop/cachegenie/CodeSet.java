package dev.gruff.hardstop.cachegenie;

import dev.gruff.hardstop.api.HSClass;
import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.cachegenie.utils.FileChecks;
import dev.gruff.hardstop.core.builder.ClassReader;
import dev.gruff.hardstop.core.impl.HSClassImpl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class CodeSet {

    private final Map<File,HSClass> sourceMap=new HashMap<>();
    private final Map<String,HSClass> nameMap=new HashMap<>();

    public Set<HSClass> unknownClasses(CodeSet c1) {

        Set<HSClass> unknown=new HashSet<>();
        for(String name:c1.nameMap.keySet()) {
            if(!nameMap.containsKey(name)) {
                unknown.add(c1.nameMap.get(name));
            }
        }
        return unknown;
    }

    public int size() {
        return nameMap.size();
    }
    public Map<String, HSClass> getNameMap() {
        return nameMap;
    }

    public Stream<HSClass> stream() {
        return nameMap.values().stream();
    }

    public HSClass clazzByName(String s) {
        return nameMap.get(s);
    }

    public static final class CodeSetBuilder {

        private Set<File> code=new HashSet<>();
        public CodeSetBuilder code(File f) {
            FileChecks.checkFileExists("f",f);
            code.add(f);
            return this;
        }
        public CodeSet build() {

            CodeSet cs=new CodeSet();
            for(File f:code) {
                System.out.println("reading from "+f.getAbsolutePath());
                try(JarFile j=new JarFile(f)) {
                        j.stream().filter(je -> { return je.getName().toLowerCase().endsWith(".class");} )
                        .forEach(je -> {
                            try (InputStream is=j.getInputStream(je)) {

                                HSClass hc=ClassReader.readClass(is);
                                cs.addClass(f,hc);
                               // System.out.println(hc.className());
                            } catch (IOException e) {
                               e.printStackTrace();
                            }

                        });

                } catch(IOException ioe) {
                    ioe.printStackTrace();
                }
            }
            return cs;
        }
    }

    private void addClass(File f, HSClass hc) {
        sourceMap.put(f,hc);
        nameMap.put(hc.className(),hc);
    }

    public static CodeSetBuilder Builder() {
        return new CodeSetBuilder();
    }
}
