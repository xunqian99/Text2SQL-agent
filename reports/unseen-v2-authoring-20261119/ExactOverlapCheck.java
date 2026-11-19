import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.text.Normalizer;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ExactOverlapCheck {
  static String normalize(String s) {
    return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");
  }
  static void collect(Object obj, List<Map<?,?>> rows) {
    if(obj instanceof Map<?,?> m) {
      if(m.get("question") instanceof String && m.get("id")!=null) rows.add(m);
      for(Object v:m.values()) if(v instanceof Map || v instanceof List) collect(v,rows);
    } else if(obj instanceof List<?> ls) for(Object v:ls) collect(v,rows);
  }
  static String js(Object x) {return "\""+x.toString().replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    LoaderOptions options=new LoaderOptions(); options.setCodePointLimit(50_000_000); options.setAllowDuplicateKeys(false);
    Yaml yaml=new Yaml(new SafeConstructor(options));
    List<Map<?,?>> own=new ArrayList<>();
    try(Stream<Path> ps=Files.walk(root.resolve("data/blind/v2-unseen-20261119"))) {
      for(Path p:ps.filter(Files::isRegularFile).filter(p->p.toString().matches(".*\\.ya?ml$")).toList()) collect(yaml.load(Files.readString(p,StandardCharsets.UTF_8)),own);
    }
    Set<Path> oldFiles=new TreeSet<>();
    for(String dir:List.of("data/eval","data/holdout","data/benchmarks")) {
      Path path=root.resolve(dir); if(Files.exists(path)) try(Stream<Path> ps=Files.walk(path)) {
        ps.filter(Files::isRegularFile).filter(p->p.toString().matches(".*\\.(ya?ml|json)$")).forEach(oldFiles::add);
      }
    }
    Path res=root.resolve("src/main/resources");
    if(Files.exists(res)) try(Stream<Path> ps=Files.walk(res)) {
      ps.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).matches(".*few.?shot.*\\.(ya?ml|json)$")).forEach(oldFiles::add);
    }
    Path data=root.resolve("data");
    if(Files.exists(data)) try(Stream<Path> ps=Files.walk(data)) {
      ps.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).matches(".*few.?shot.*\\.(ya?ml|json)$")).forEach(oldFiles::add);
    }
    Set<String> oldIds=new HashSet<>(),oldQuestions=new HashSet<>();
    int oldRecords=0,parsedFiles=0,parseErrors=0;
    for(Path p:oldFiles) {
      try {
        List<Map<?,?>> rows=new ArrayList<>();
        collect(yaml.load(Files.readString(p,StandardCharsets.UTF_8).replace("\uFEFF","")),rows);
        for(Map<?,?> m:rows){oldIds.add(m.get("id").toString());oldQuestions.add(normalize(m.get("question").toString()));}
        oldRecords+=rows.size();parsedFiles++;
      } catch(Exception ex) {parseErrors++;}
    }
    Set<String> newIds=new HashSet<>(),newQuestions=new HashSet<>();
    List<String> idHits=new ArrayList<>(),questionHits=new ArrayList<>();int duplicateIds=0,duplicateQuestions=0;
    for(Map<?,?> m:own) {
      String id=m.get("id").toString(),q=normalize(m.get("question").toString());
      if(oldIds.contains(id)) idHits.add(id); if(oldQuestions.contains(q)) questionHits.add(id);
      if(!newIds.add(id)) duplicateIds++;if(!newQuestions.add(q))duplicateQuestions++;
    }
    String result="{\n  \"format\": \"normalized-exact-overlap-check-v1\",\n  \"normalization\": \"NFKC, lowercase, remove Unicode punctuation/separators/whitespace\",\n  \"mode\": \"programmatic parsing only; no old content emitted\",\n  \"sourceDirectories\": [\"data/eval\",\"data/holdout\",\"data/benchmarks\",\"src/main/resources/**/*fewshot*.yaml or json\"],\n  \"newRecords\": "+own.size()+",\n  \"oldFilesDiscovered\": "+oldFiles.size()+",\n  \"oldFilesParsed\": "+parsedFiles+",\n  \"oldParseErrors\": "+parseErrors+",\n  \"oldRecordsCompared\": "+oldRecords+",\n  \"idOverlapCount\": "+idHits.size()+",\n  \"normalizedQuestionOverlapCount\": "+questionHits.size()+",\n  \"newDuplicateIds\": "+duplicateIds+",\n  \"newDuplicateQuestions\": "+duplicateQuestions+",\n  \"newIdsWithIdOverlap\": ["+String.join(",",idHits.stream().map(ExactOverlapCheck::js).toList())+"],\n  \"newIdsWithQuestionOverlap\": ["+String.join(",",questionHits.stream().map(ExactOverlapCheck::js).toList())+"]\n}\n";
    Files.writeString(root.resolve("reports/unseen-v2-authoring-20261119/duplicate-check.json"),result,StandardCharsets.UTF_8);
    System.out.print(result);
    if(parseErrors>0 || idHits.size()>0 || questionHits.size()>0 || duplicateIds>0 || duplicateQuestions>0) System.exit(2);
  }
}
