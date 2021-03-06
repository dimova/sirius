/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.jdbc;

import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import sirius.kernel.commons.Context;
import sirius.kernel.commons.Watch;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a flexible way of executing parameterized SQL queries without
 * thinking too much about resource management.
 * <p>
 * Supports named parameters in form of ${name}. Also #{name} can be used in LIKE expressions and will be
 * surrounded by % signs (if not empty).
 * </p>
 * <p>
 * Optional blocks can be surrounded with angular braces: SELECT * FROM x WHERE test = 1[ AND test2=${val}]
 * The surrounded block will only be added to the query, if the parameter within has a non-null value.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/11
 */
public class SQLQuery {

    public interface RowHandler {
        boolean handle(Row row);
    }

    private final Database ds;
    private final String sql;
    private Context params = Context.create();

    /*
     * Create a new instance using Databases.createQuery(sql)
     */
    protected SQLQuery(Database ds, String sql) {
        this.ds = ds;
        this.sql = sql;
    }

    /**
     * Adds a parameter.
     *
     * @param parameter the name of the parameter as referenced in the SQL statement (${name} or #{name}).
     * @param value     the value of the parameter
     * @return the query itself to support fluent calls
     */
    public SQLQuery set(String parameter, Object value) {
        params.put(parameter, value);
        return this;
    }

    /**
     * Sets all parameters of the given context.
     *
     * @param ctx the containing pairs of names and values to add to the query
     * @return the query itself to support fluent calls
     */
    public SQLQuery set(Map<String, Object> ctx) {
        params.putAll(ctx);
        return this;
    }

    /**
     * Executes the given query returning the result as list
     *
     * @return a list of {@link Row}s
     * @throws SQLException in case of a database error
     */
    @Nonnull
    public List<Row> queryList() throws SQLException {
        return queryList(0);
    }

    /**
     * Executes the given query returning the result as list with at most <tt>maxRows</tt> entries
     *
     * @param maxRows maximal number of rows to be returned
     * @return a list of {@link Row}s
     * @throws SQLException in case of a database error
     */
    @Nonnull
    public List<Row> queryList(int maxRows) throws SQLException {
        Watch w = Watch.start();
        Connection c = ds.getConnection();
        List<Row> result = Lists.newArrayList();
        try {
            SQLStatementStrategy sa = new SQLStatementStrategy(c, ds.isMySQL());
            StatementCompiler.buildParameterizedStatement(sa, sql, params);
            if (sa.getStmt() == null) {
                return result;
            }
            if (maxRows > 0) {
                sa.getStmt().setMaxRows(maxRows);
            }
            ResultSet rs = sa.getStmt().executeQuery();
            try {
                while (rs.next()) {
                    Row row = new Row();
                    for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                        row.fields.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
                    }
                    result.add(row);
                }
                return result;
            } finally {
                rs.close();
                sa.getStmt().close();
            }

        } finally {
            c.close();
            w.submitMicroTiming("SQL", sql);
        }
    }

    /**
     * Executes the given query by invoking the {@link RowHandler} for each
     * result row.
     *
     * @param handler the row handler invoked for each row
     * @throws SQLException in case of a database error
     */
    public void perform(RowHandler handler, int maxRows) throws SQLException {
        Watch w = Watch.start();
        Connection c = ds.getConnection();
        try {
            SQLStatementStrategy sa = new SQLStatementStrategy(c, ds.isMySQL());
            StatementCompiler.buildParameterizedStatement(sa, sql, params);
            if (sa.getStmt() == null) {
                return;
            }
            if (maxRows > 0) {
                sa.getStmt().setMaxRows(maxRows);
            }
            ResultSet rs = sa.getStmt().executeQuery();
            try {
                while (rs.next()) {
                    Row row = new Row();
                    for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                        row.fields.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
                    }
                    if (!handler.handle(row)) {
                        break;
                    }
                }
            } finally {
                rs.close();
                sa.getStmt().close();
            }

        } finally {
            c.close();
            w.submitMicroTiming("SQL", sql);
        }
    }

    /**
     * Executes the given query returning the first matching row.
     * <p>
     * If the resulting row contains a {@link Blob} an {@link OutputStream} as to be passed in as parameter
     * with the name name as the column. The contents of the blob will then be written into the given
     * output stream (without closing it).
     * </p>
     *
     * @return the first matching row for the given query or <tt>null</tt> if no matching row was found
     * @throws SQLException in case of a database error
     */
    @Nullable
    public Row queryFirst() throws SQLException {
        Connection c = ds.getConnection();
        Watch w = Watch.start();
        try {
            SQLStatementStrategy sa = new SQLStatementStrategy(c, ds.isMySQL());
            StatementCompiler.buildParameterizedStatement(sa, sql, params);
            if (sa.getStmt() == null) {
                return null;
            }
            ResultSet rs = sa.getStmt().executeQuery();
            try {
                if (rs.next()) {
                    Row row = new Row();
                    for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                        Object obj = rs.getObject(col);
                        if (obj instanceof Blob) {
                            writeBlobToParameter(rs, col, (Blob) obj);
                        } else {
                            row.fields.put(rs.getMetaData().getColumnLabel(col), obj);
                        }
                    }
                    return row;
                }
                return null;
            } finally {
                rs.close();
                sa.getStmt().close();
            }

        } finally {
            c.close();
            w.submitMicroTiming("SQL", sql);
        }
    }

    /**
     * Executes the given query returning the first matching row wrapped as {@link java.util.Optional}.
     * <p>
     * This method behaves like {@link #queryFirst()} but returns an optional value instead of {@link null}.
     * </p>
     *
     * @return the resulting row wrapped as optional, or an empty optional if no matching row was found.
     * @throws SQLException in case of a database error
     */
    @Nonnull
    public Optional<Row> first() throws SQLException {
        return Optional.ofNullable(queryFirst());
    }

    /*
     * If a Blob is inside a result set, we expect an OutputStream as parameter with the same name which we write
     * the data to.
     */
    private void writeBlobToParameter(ResultSet rs, int col, Blob blob) throws SQLException {
        OutputStream out = (OutputStream) params.get(rs.getMetaData().getColumnLabel(col));
        if (out != null) {
            try {
                InputStream in = blob.getBinaryStream();
                try {
                    ByteStreams.copy(in, out);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }
    }

    /**
     * Executes the query as update.
     * <p>
     * Requires the SQL statement to be an UPDATE or DELETE statement.
     * </p>
     *
     * @return the number of rows changed
     * @throws SQLException in case of a database error
     */
    public int executeUpdate() throws SQLException {
        Connection c = ds.getConnection();
        Watch w = Watch.start();

        try {
            SQLStatementStrategy sa = new SQLStatementStrategy(c, ds.isMySQL());
            StatementCompiler.buildParameterizedStatement(sa, sql, params);
            if (sa.getStmt() == null) {
                return 0;
            }
            try {
                return sa.getStmt().executeUpdate();
            } finally {
                sa.getStmt().close();
            }
        } finally {
            c.close();
            w.submitMicroTiming("SQL", sql);
        }
    }

    /**
     * Executes the update and returns the generated keys.
     * <p>
     * Requires the SQL statement to be an UPDATE or DELETE statement.
     * </p>
     *
     * @return the a row representing all generated keys
     * @throws SQLException in case of a database error
     */
    public Row executeUpdateReturnKeys() throws SQLException {
        Connection c = ds.getConnection();
        Watch w = Watch.start();

        try {
            SQLStatementStrategy sa = new SQLStatementStrategy(c, ds.isMySQL());
            sa.setRetrieveGeneratedKeys(true);
            StatementCompiler.buildParameterizedStatement(sa, sql, params);
            if (sa.getStmt() == null) {
                return new Row();
            }
            try {
                sa.getStmt().executeUpdate();
                ResultSet rs = sa.getStmt().getGeneratedKeys();
                try {
                    Row row = new Row();
                    if (rs.next()) {
                        for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                            row.fields.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
                        }
                    }
                    return row;
                } finally {
                    rs.close();
                }
            } finally {
                sa.getStmt().close();
            }
        } finally {
            c.close();
            w.submitMicroTiming("SQL", sql);
        }
    }

    @Override
    public String toString() {
        return "JDBCQuery [" + sql + "]";
    }

}
