/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.app.oma.schema;

import sirius.kernel.commons.ComparableTuple;
import sirius.kernel.commons.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a foreign key.
 */
public class ForeignKey {
    private String name;
    private List<ComparableTuple<Integer, String>> keyFields = new ArrayList<ComparableTuple<Integer, String>>();
    private List<ComparableTuple<Integer, String>> foreignKeyFields = new ArrayList<ComparableTuple<Integer, String>>();
    private String foreignTable;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getForeignTable() {
        return foreignTable;
    }

    public void setForeignTable(String foreignTable) {
        this.foreignTable = foreignTable;
    }

    public List<String> getColumns() {
        List<String> columns = new ArrayList<String>();
        for (ComparableTuple<Integer, String> field : keyFields) {
            columns.add(field.getSecond());
        }
        return columns;
    }

    public List<String> getForeignColumns() {
        List<String> columns = new ArrayList<String>();
        for (ComparableTuple<Integer, String> field : foreignKeyFields) {
            columns.add(field.getSecond());
        }
        return columns;
    }

    @Override
    public String toString() {
        return name + "(" + Strings.join(getColumns(), ", ") + ") -> " + foreignTable + " (" + Strings.join(
                getForeignColumns(),
                ", ") + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof ForeignKey)) {
            return false;
        }
        return Strings.equalIgnoreCase(((ForeignKey) obj).name, name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }

    public void addColumn(int pos, String field) {
        keyFields.add(ComparableTuple.create(pos, field));
        Collections.sort(keyFields);
    }

    public void addForeignColumn(int pos, String field) {
        foreignKeyFields.add(ComparableTuple.create(pos, field));
        Collections.sort(foreignKeyFields);
    }

}
