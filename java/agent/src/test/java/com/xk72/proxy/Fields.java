package com.xk72.proxy;

import java.util.ArrayList;
import java.util.List;

/** Test double for header collections. */
public class Fields {
    private final List<String[]> fields = new ArrayList<>();

    public Fields add(String name, String value) {
        fields.add(new String[]{name, value});
        return this;
    }

    public int getFieldCount() {
        return fields.size();
    }

    public String getFieldName(int i) {
        return fields.get(i)[0];
    }

    public String getFieldValue(int i) {
        return fields.get(i)[1];
    }
}
