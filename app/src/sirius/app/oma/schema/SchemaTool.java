/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.app.oma.schema;

import com.google.common.base.Objects;
import sirius.kernel.commons.ComparableTuple;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Helper class for manipulating and inspecting db metadata.
 */
public class SchemaTool {

    private final String catalog;
    private final DatabaseDialect dialect;

    public SchemaTool(String catalog, DatabaseDialect dialect) {
        this.catalog = catalog;
        this.dialect = dialect;
    }

    /**
     * Reads the DB-schema for the given connection.
     */
    public List<Table> getSchema(Connection c) throws SQLException {
        List<Table> tables = new ArrayList<Table>();
        ResultSet rs = c.getMetaData().getTables(catalog, null, null, null);
        while (rs.next()) {
            if ("TABLE".equalsIgnoreCase(rs.getString(4))) {
                Table table = new Table();
                table.setName(rs.getString("TABLE_NAME"));
                fillTable(c, table);
                tables.add(dialect.completeTableInfos(table));
            }
        }
        rs.close();
        return tables;
    }

    private void fillTable(Connection c, Table table) throws SQLException {
        fillColumns(c, table);
        fillPK(c, table);
        fillIndices(c, table);
        fillFKs(c, table);
    }

    private void fillFKs(Connection c, Table table) throws SQLException {
        ResultSet rs;
        // FKs
        rs = c.getMetaData().getImportedKeys(catalog, null, table.getName());
        while (rs.next()) {
            String indexName = rs.getString("FK_NAME");
            if (indexName != null) {
                ForeignKey fk = table.getFK(indexName);
                if (fk == null) {
                    fk = new ForeignKey();
                    fk.setName(indexName);
                    fk.setForeignTable(rs.getString("PKTABLE_NAME"));
                    table.getForeignKeys().add(fk);
                }
                fk.addColumn(rs.getInt("KEY_SEQ"), rs.getString("FKCOLUMN_NAME"));
                fk.addForeignColumn(rs.getInt("KEY_SEQ"), rs.getString("PKCOLUMN_NAME"));
            }
        }
        rs.close();
    }

    private void fillIndices(Connection c, Table table) throws SQLException {
        // Indices
        ResultSet rs = c.getMetaData().getIndexInfo(catalog, null, table.getName(), false, false);
        while (rs.next()) {
            String indexName = rs.getString("INDEX_NAME");
            if (indexName != null) {
                Key key = table.getKey(indexName);
                if (key == null) {
                    key = new Key();
                    key.setName(indexName);
                    table.getKeys().add(key);
                }
                key.addColumn(rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
            }
        }
        rs.close();
    }

    private void fillPK(Connection c, Table table) throws SQLException {
        // PKs
        ResultSet rs = c.getMetaData().getPrimaryKeys(catalog, null, table.getName());
        List<ComparableTuple<Integer, String>> keyFields = new ArrayList<ComparableTuple<Integer, String>>();
        while (rs.next()) {
            keyFields.add(ComparableTuple.create(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME")));
        }
        Collections.sort(keyFields);
        for (Tuple<Integer, String> key : keyFields) {
            table.getPrimaryKey().add(key.getSecond());
        }
        rs.close();
    }

    private void fillColumns(Connection c, Table table) throws SQLException {
        // Columns
        ResultSet rs = c.getMetaData().getColumns(catalog, null, table.getName(), null);
        while (rs.next()) {
            Column column = new Column();
            column.setName(rs.getString("COLUMN_NAME"));
            column.setNullable(DatabaseMetaData.columnNullable == rs.getInt("NULLABLE"));
            column.setType(rs.getInt("DATA_TYPE"));
            column.setLength(rs.getInt("COLUMN_SIZE"));
            column.setPrecision(rs.getInt("DECIMAL_DIGITS"));
            column.setScale(rs.getInt("COLUMN_SIZE"));
            column.setDefaultValue(rs.getString("COLUMN_DEF"));
            table.getColumns().add(column);
        }
        rs.close();
    }

    public List<SchemaUpdateAction> migrateSchemaTo(Connection c,
                                                    List<Table> targetSchema,
                                                    boolean dropTables) throws SQLException {
        List<SchemaUpdateAction> result = new ArrayList<SchemaUpdateAction>();
        List<Table> currentSchema = getSchema(c);

        List<Table> sortedTarget = new ArrayList<Table>(targetSchema);
        sort(sortedTarget);
        // Sync required tables...
        for (Table targetTable : sortedTarget) {
            Table other = findInList(currentSchema, targetTable);
            if (other == null) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The table '%s' does not exist", targetTable.getName()));
                action.setDataLossPossible(false);
                action.setSql(dialect.generateCreateTable(targetTable));
                result.add(action);
            } else {
                syncTables(targetTable, other, result);
            }
        }
        if (dropTables) {
            // Drop unused tables...
            for (Table table : currentSchema) {
                if (findInList(targetSchema, table) == null) {
                    SchemaUpdateAction action = new SchemaUpdateAction();
                    action.setReason(Strings.apply("The table '%s' is no longer used", table.getName()));
                    action.setDataLossPossible(true);
                    action.setSql(dialect.generateDropTable(table));
                    result.add(action);
                }
            }
        }
        return result;
    }

    /**
     * Sorts the order of tables, so that a table is handled before it is
     * referenced via a foreign key.
     */
    private void sort(List<Table> sortedTarget) {
        Set<String> handled = new TreeSet<String>();
        int index = 0;
        // In a worst case, we have to compare each with every table O(n^2).
        // After
        // that, we can assume that there is a deadlock (circular reference) and
        // exit.
        int maxOperations = sortedTarget.size() * sortedTarget.size();
        while (index < sortedTarget.size() && maxOperations-- > 0) {
            Table t = sortedTarget.get(index);
            if (hasOpenReferences(t, handled)) {
                // Table has open references, push to end...
                sortedTarget.remove(index);
                sortedTarget.add(t);
            } else {
                // Table is ok, check next...
                index++;
                handled.add(t.getName());
            }
        }

    }

    private boolean hasOpenReferences(Table t, Set<String> handled) {
        for (ForeignKey fk : t.getForeignKeys()) {
            if (!handled.contains(fk.getForeignTable())) {
                return true;
            }
        }
        return false;
    }

    private boolean keyListEqual(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void syncTables(Table targetTable, Table other, List<SchemaUpdateAction> result) {
        syncColumns(targetTable, other, result);
        syncForeignKeys(targetTable, other, result);
        syncKeys(targetTable, other, result);
        if (!keyListEqual(targetTable.getPrimaryKey(), other.getPrimaryKey())) {
            SchemaUpdateAction action = new SchemaUpdateAction();
            action.setReason(Strings.apply("The primary key of '%s' changed.", targetTable.getName()));
            action.setDataLossPossible(true);
            action.setSql(dialect.generateAlterPrimaryKey(targetTable));
            result.add(action);
        }
    }

    private void syncKeys(Table targetTable, Table other, List<SchemaUpdateAction> result) {
        for (Key targetKey : targetTable.getKeys()) {
            Key otherKey = findInList(other.getKeys(), targetKey);
            if (otherKey == null) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The index '%s' of table '%s' does not exist.",
                                               targetKey.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(false);
                action.setSql(dialect.generateAddKey(targetTable, targetKey));
                result.add(action);
            } else {
                if (!keyListEqual(targetKey.getColumns(), otherKey.getColumns())) {
                    SchemaUpdateAction action = new SchemaUpdateAction();
                    action.setReason(Strings.apply("The index '%s' of table '%s' needs a change.",
                                                   targetKey.getName(),
                                                   targetTable.getName()));
                    action.setDataLossPossible(true);
                    action.setSql(dialect.generateAlterKey(targetTable, otherKey, targetKey));
                    result.add(action);
                }
            }
        }
        // Drop unused keys...
        for (Key key : other.getKeys()) {
            ForeignKey fk = new ForeignKey();
            fk.setName(key.getName());
            if (findInList(targetTable.getKeys(), key) == null && findInList(targetTable.getForeignKeys(),
                                                                             fk) == null && dialect.shouldDropKey(
                    targetTable,
                    other,
                    key)) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The index '%s' of table '%s' is no longer used.",
                                               key.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(true);
                action.setSql(dialect.generateDropKey(other, key));
                result.add(action);
            }
        }
    }

    private void syncForeignKeys(Table targetTable, Table other, List<SchemaUpdateAction> result) {
        for (ForeignKey targetKey : targetTable.getForeignKeys()) {
            ForeignKey otherKey = findInList(other.getForeignKeys(), targetKey);
            if (otherKey == null) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The foreign key '%s' of table '%s' does not exist.",
                                               targetKey.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(false);
                action.setSql(dialect.generateAddForeignKey(targetTable, targetKey));
                result.add(action);
            } else {
                if (!keyListEqual(targetKey.getColumns(),
                                  otherKey.getColumns()) || !keyListEqual(targetKey.getForeignColumns(),
                                                                          otherKey.getForeignColumns()) || !Strings.equalIgnoreCase(
                        targetKey.getForeignTable(),
                        otherKey.getForeignTable())) {
                    SchemaUpdateAction action = new SchemaUpdateAction();
                    action.setReason(Strings.apply("The foreign key '%s' of table '%s' needs a change.",
                                                   targetKey.getName(),
                                                   targetTable.getName()));
                    action.setDataLossPossible(true);
                    action.setSql(dialect.generateAlterForeignKey(targetTable, otherKey, targetKey));
                    result.add(action);
                }
            }
        }
        // Drop unused keys...
        for (ForeignKey key : other.getForeignKeys()) {
            if (findInList(targetTable.getForeignKeys(), key) == null) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The foreign key '%s' of table '%s' is no longer used.",
                                               key.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(true);
                action.setSql(dialect.generateDropForeignKey(other, key));
                result.add(action);
            }
        }
    }

    private void syncColumns(Table targetTable, Table other, List<SchemaUpdateAction> result) {
        Set<String> usedColumns = new TreeSet<String>();
        for (Column targetCol : targetTable.getColumns()) {
            // Try to find column by name
            Column otherCol = findColumn(other, targetCol.getName());
            // If we didn't find a column and the col has rename infos, try to
            // find an appropriate column for renaming.
            if (otherCol == null && targetCol.getOldName() != null) {
                otherCol = findColumn(other, targetCol.getOldName());
            }
            if (otherCol == null) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The column '%s' of table '%s' does not exist.",
                                               targetCol.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(false);
                action.setSql(dialect.generateAddColumn(targetTable, targetCol));
                result.add(action);
            } else {
                usedColumns.add(otherCol.getName());
                String reason = dialect.areColumnsEqual(targetCol, otherCol);
                // Check for renaming...
                if (reason == null && !Objects.equal(targetCol.getName(),
                                                     otherCol.getName()) && dialect.isColumnCaseSensitive()) {
                    reason = Strings.apply("The column '%s' of table '%s' needs to be renamed",
                                           targetCol.getName(),
                                           targetTable.getName());
                } else if (reason != null) {
                    reason = Strings.apply("The column '%s' of table '%s' needs a change: %s",
                                           targetCol.getName(),
                                           targetTable.getName(),
                                           reason);
                }
                if (reason != null) {
                    SchemaUpdateAction action = new SchemaUpdateAction();
                    action.setReason(reason);
                    action.setDataLossPossible(true);
                    action.setSql(dialect.generateAlterColumnTo(targetTable, targetCol.getOldName(), targetCol));
                    result.add(action);
                }
            }
        }
        for (Column col : other.getColumns()) {
            if (!usedColumns.contains(col.getName())) {
                SchemaUpdateAction action = new SchemaUpdateAction();
                action.setReason(Strings.apply("The column '%s' of table '%s' nis no longer used",
                                               col.getName(),
                                               targetTable.getName()));
                action.setDataLossPossible(true);
                action.setSql(dialect.generateDropColumn(targetTable, col));
                result.add(action);
            }
        }
    }

    private Column findColumn(Table other, String name) {
        name = dialect.translateColumnName(name);
        for (Column col : other.getColumns()) {
            if (Objects.equal(col.getName(), name)) {
                return col;
            }
        }
        return null;
    }

    private <X> X findInList(List<X> list, X obj) {
        int index = list.indexOf(obj);
        if (index == -1) {
            return null;
        }
        return list.get(index);
    }

    public static String getJdbcTypeName(int jdbcType) {
        // Use reflection to populate a map of int values to names
        if (map == null) {
            map = new HashMap<Integer, String>();
            // Get all field in java.sql.Types
            Field[] fields = java.sql.Types.class.getFields();
            for (int i = 0; i < fields.length; i++) {
                try {
                    // Get field name
                    String name = fields[i].getName();
                    // Get field value
                    Integer value = (Integer) fields[i].get(null);
                    // Add to map
                    map.put(value, name);
                } catch (IllegalAccessException e) {
                }
            }
        }
        // Return the JDBC type name
        return map.get(jdbcType);
    }

    private static Map<Integer, String> map;
}
