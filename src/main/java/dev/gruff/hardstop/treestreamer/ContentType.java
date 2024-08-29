package dev.gruff.hardstop.treestreamer;

public enum ContentType {

    HTML, XML;

    public boolean match(String s) {

        switch (this) {
            case HTML -> {
                return s!=null && s.toLowerCase().contains("text/html");
            }
            case XML -> {
                return s!=null && s.toLowerCase().contains("text/xml");
            }
        }
        return false;
    }
}
