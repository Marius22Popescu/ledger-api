package com.ledger.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

final class SqlTimes {

    private SqlTimes() {
    }

    static void write(PreparedStatement statement, int index, Instant instant) throws java.sql.SQLException {
        statement.setTimestamp(index, Timestamp.from(instant), utc());
    }

    static Instant read(ResultSet resultSet, String column) throws java.sql.SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column, utc());
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private static Calendar utc() {
        return new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    }
}
