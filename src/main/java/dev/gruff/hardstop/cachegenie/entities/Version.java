package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.utils.StringChecks;

public  sealed interface Version {

    public static final Version EMPTY_VERSION=new VersionImpl();

    static Version createWithDefault(String versionRef) {

        if(StringChecks.isNullOrEmpty(versionRef)) return EMPTY_VERSION;
        return new VersionImpl(versionRef);
    }

    public String value();


    public static Version create(String v) {
        return new VersionImpl(v);
    }

    final class VersionImpl implements Version {

        private String value;

        private  VersionImpl(String vers) {
            StringChecks.checkNonNullNotEmpty("version",vers);
            this.value=vers;
        }

        private  VersionImpl() {
            value="";
        }

        @Override
        public String value() {
            return value;
        }
    }
}
