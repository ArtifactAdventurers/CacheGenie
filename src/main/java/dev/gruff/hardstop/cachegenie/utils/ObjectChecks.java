package dev.gruff.hardstop.cachegenie.utils;

public class ObjectChecks {

    public static void isPresent(String name,Object value) {
        if(value==null) throw new RuntimeException(name+" is null");
    }
}
