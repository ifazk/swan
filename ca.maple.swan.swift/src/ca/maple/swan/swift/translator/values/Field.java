package ca.maple.swan.swift.translator.values;

import com.ibm.wala.cast.tree.impl.CAstNodeTypeMapRecorder;

public class Field extends SILValue {

    private final SILValue object;
    private final Object field;

    public Field(String name, String type, CAstNodeTypeMapRecorder typeRecorder, SILValue object, Object fieldName) {
        super(name, type, typeRecorder);
        this.object = object;
        this.field = fieldName;
    }

    public SILValue getObject() {
        return object;
    }

    public Object getField() {
        return field;
    }
}
