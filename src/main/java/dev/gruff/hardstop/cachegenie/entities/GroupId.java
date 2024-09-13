package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.utils.StringChecks;

public interface GroupId {

    public static final GroupId EMPTY_GROUPID=new GroupIDImpl();

    static GroupId create(String groupIDRef) {
        return new GroupIDImpl(groupIDRef);
    }

    static GroupId createWithDefault(String groupIDRef) {
        if(StringChecks.isNullOrEmpty(groupIDRef)) return EMPTY_GROUPID;
       return new GroupIDImpl(groupIDRef);

    }

    public String value();

    final class GroupIDImpl implements GroupId {

        private String value;

        private  GroupIDImpl(String groupIDRef) {
            StringChecks.checkNonNullNotEmpty("groupID",groupIDRef);
            this.value=groupIDRef;
        }

        public GroupIDImpl() {
            value="";
        }

        @Override
        public String value() {
            return value;
        }
    }
}
