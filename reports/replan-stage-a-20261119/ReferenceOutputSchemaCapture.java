import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Describes only the two public V4 Gold outputs through a read-only connection. */
public final class ReferenceOutputSchemaCapture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static String env(String name,String fallback) {
        String value=System.getenv(name); return value==null||value.isBlank()?fallback:value;
    }
    private static String kind(int type) {
        return switch(type) {
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.FLOAT,Types.REAL,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL -> "NUMERIC";
            case Types.DATE,Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE,Types.TIME,Types.TIME_WITH_TIMEZONE -> "TEMPORAL";
            default -> "TEXT";
        };
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Expected new output JSON path");
        Path output=Path.of(args[0]);
        if(Files.exists(output)) throw new IllegalArgumentException("Refuse to overwrite typed column capture");
        var items=new LinkedHashMap<String,Object>();
        Path specification=Path.of("reports/replan-stage-a-20261119/contract-audit/item-recommendations.json");
        var references=JSON.readTree(specification.toFile()).path("items");
        if(references.size()!=200) throw new IllegalStateException("Expected 200 audited public reference specifications");
        try(var connection=DriverManager.getConnection(env("TEXT2SQL_TEST_DB_URL","jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER","text2sql_ro"),env("TEXT2SQL_TEST_DB_PASSWORD","text2sql_ro"))) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) { statement.execute("SET LOCAL statement_timeout='8s'"); }
                for(var item:references) {
                    String suite=item.path("suite").asText(), id=item.path("id").asText();
                    if(!List.of("development","formal").contains(suite) || items.containsKey(suite+":"+id))
                        throw new IllegalStateException("Unknown or duplicate public reference identity");
                    String goldSql=item.path("proposedGoldSql").asText();
                    String sql=goldSql.strip().replaceFirst(";\\s*$","");
                    try(var statement=connection.prepareStatement("SELECT * FROM ("+sql+") AS reference_output_contract LIMIT 0")) {
                        var metadata=statement.getMetaData();
                        var columns=new ArrayList<Map<String,Object>>();
                        for(int i=1;i<=metadata.getColumnCount();i++)
                            columns.add(Map.of("position",i-1,"name",metadata.getColumnLabel(i),"jdbcType",metadata.getColumnType(i),
                                "postgresType",metadata.getColumnTypeName(i),"kind",kind(metadata.getColumnType(i))));
                        items.put(suite+":"+id,Map.of("questionSha256",sha(item.path("proposedQuestion").asText()),
                            "goldSqlSha256",sha(goldSql),"columns",columns));
                    }
                }
            connection.rollback();
        }
        try(var stream=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(stream,Map.of("format","public-gold-output-column-types-v1", "items",items,
                "sourceSpecificationSha256",sha(Files.readString(specification)),
                "capturedBy","audited proposed public Gold, read-only JDBC prepared statement metadata; LIMIT 0 wrapper; no result rows read", "providerCalls",0));
        }
        System.out.println("OUTPUT_SCHEMA items="+items.size()+" providerCalls=0");
    }
}
