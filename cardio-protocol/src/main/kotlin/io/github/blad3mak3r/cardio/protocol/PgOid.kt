package io.github.blad3mak3r.cardio.protocol

object PgOid {
    const val BOOL        = 16
    const val BYTEA       = 17
    const val CHAR        = 18
    const val INT8        = 20
    const val INT2        = 21
    const val INT4        = 23
    const val TEXT        = 25
    const val OID         = 26
    const val FLOAT4      = 700
    const val FLOAT8      = 701
    const val UNKNOWN     = 705
    const val INET        = 869
    const val CIDR        = 650
    const val MACADDR     = 829
    const val MACADDR8    = 774
    const val BPCHAR      = 1042  // char(n)
    const val VARCHAR     = 1043
    const val DATE        = 1082
    const val TIME        = 1083
    const val TIMESTAMP   = 1114
    const val TIMESTAMPTZ = 1184
    const val INTERVAL    = 1186
    const val TIMETZ      = 1266
    const val NUMERIC     = 1700
    const val UUID        = 2950
    const val INT4RANGE   = 3904
    const val NUMRANGE    = 3906
    const val TSRANGE     = 3908
    const val TSTZRANGE   = 3910
    const val DATERANGE   = 3912
    const val JSONB       = 3802
    const val JSON        = 114
    const val INT8RANGE   = 3926

    // Arrays (OID = base_type + 1 in many cases, but we make them explicit)
    const val BOOL_ARRAY        = 1000
    const val INT2_ARRAY        = 1005
    const val INT4_ARRAY        = 1007
    const val INT8_ARRAY        = 1016
    const val TEXT_ARRAY        = 1009
    const val VARCHAR_ARRAY     = 1015
    const val FLOAT4_ARRAY      = 1021
    const val FLOAT8_ARRAY      = 1022
    const val JSON_ARRAY        = 199
    const val INET_ARRAY        = 1041
    const val CIDR_ARRAY        = 651
    const val MACADDR_ARRAY     = 1040
    const val MACADDR8_ARRAY    = 775
    const val TIMESTAMP_ARRAY   = 1115
    const val TIMESTAMPTZ_ARRAY = 1185
    const val INTERVAL_ARRAY    = 1187
    const val NUMERIC_ARRAY     = 1231
    const val UUID_ARRAY        = 2951
}