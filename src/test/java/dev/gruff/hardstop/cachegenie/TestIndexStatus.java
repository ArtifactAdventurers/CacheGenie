package dev.gruff.hardstop.cachegenie;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestIndexStatus {

    @Test
    public void test1() throws IOException {
        File root=new File(System.getProperty("user.home"));
        root=new File(root,".m2");
        root=new File(root,"cachegenie");
        IndexStatus is=IndexStatus.load(root);
    }
}
