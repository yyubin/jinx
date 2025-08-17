package org.jinx.migration.liquibase;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ChangeSetIdGenerator {
    private final String timestamp;
    private int seq = 1;

    public ChangeSetIdGenerator() {
        this.timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    public String nextId() {
        return timestamp + "-" + (seq++);
    }
}
