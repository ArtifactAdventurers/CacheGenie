package dev.gruff.hardstop.cachegenie.utils;

public class StringChecks {

    public static void checkNonNullNotEmpty(String name,String value) {
        if(value==null) error("String for "+name+" is null");
         value=value.trim();
        if(value.equals("")) error("String for "+name+" is empty");

    }


    private static void error(String msg) {
        throw new RuntimeException(msg);
    }

    public static boolean isNullOrEmpty(String s) {
            return s==null || s.trim().equals("");
    }
}
