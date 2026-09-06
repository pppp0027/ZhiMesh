package com.pppp.zhimesh.common.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps a PostgreSQL real[] column to a primitive float array. */
@MappedTypes(float[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class FloatArrayTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int index, float[] value, JdbcType jdbcType)
            throws SQLException {
        Float[] boxed = new Float[value.length];
        for (int i = 0; i < value.length; i++) boxed[i] = value[i];
        ps.setArray(index, ps.getConnection().createArrayOf("real", boxed));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getArray(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getArray(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getArray(columnIndex));
    }

    private static float[] read(Array sqlArray) throws SQLException {
        if (sqlArray == null) return null;
        try {
            Object raw = sqlArray.getArray();
            if (raw instanceof Float[] values) {
                float[] result = new float[values.length];
                for (int i = 0; i < values.length; i++) result[i] = values[i];
                return result;
            }
            if (raw instanceof Object[] values) {
                float[] result = new float[values.length];
                for (int i = 0; i < values.length; i++) {
                    result[i] = values[i] == null ? 0F : ((Number) values[i]).floatValue();
                }
                return result;
            }
            throw new SQLException("Unsupported PostgreSQL real[] representation: " + raw.getClass());
        } finally {
            sqlArray.free();
        }
    }
}
