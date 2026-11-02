package dev.atoms;

import org.mozilla.javascript.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** A version is a complete text-file snapshot, independent of language. */
final class SourceFiles {
 private SourceFiles(){}
 static boolean canPreview(Map<String,String> files) {
  return files!=null && files.keySet().containsAll(Set.of("index.html","style.css","script.js"))
   && files.keySet().stream().allMatch(name -> Set.of("index.html","style.css","script.js").contains(name)||name.toLowerCase(Locale.ROOT).endsWith(".md"));
 }
 static void validate(Map<String,String> files) {
  if(files==null||files.isEmpty()||files.size()>80)throw new IllegalArgumentException("一个版本须包含1～80个源码文件");
  long total=0;var paths=new HashSet<String>();boolean nonEmpty=false;
  for(var entry:files.entrySet()) {
   String path=entry.getKey(),content=entry.getValue();
   if(path==null||path.isBlank()||path.length()>240||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("源码路径无效：请使用相对路径和正斜杠");
   for(String part:path.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("源码路径不能包含空目录、. 或 ..");
   if(!paths.add(path.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("存在仅大小写不同的重复文件路径");
   if(content==null||content.length()>200_000)throw new IllegalArgumentException("单个源码文件不能超过200KB字符限制");
   total+=content.getBytes(StandardCharsets.UTF_8).length;nonEmpty|=!content.isBlank();
  }
  // A file and its parent directory cannot share a path on disk.
  for(String path:paths){int slash=path.indexOf('/');while(slash>=0){if(paths.contains(path.substring(0,slash)))throw new IllegalArgumentException("源码文件与目录路径冲突");slash=path.indexOf('/',slash+1);}}
  if(total>1_000_000||!nonEmpty)throw new IllegalArgumentException("源码总量不能超过1MB，且不能全部为空");
  if(canPreview(files)) {
   if(!files.get("index.html").toLowerCase(Locale.ROOT).contains("<body"))throw new IllegalArgumentException("HTML缺少body");
   var env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);
   try{new Parser(env).parse(files.get("script.js"),"script.js",1);}catch(EvaluatorException e){throw new IllegalArgumentException("JavaScript语法错误："+e.getMessage());}
  }
 }
 static byte[] zip(Map<String,String> files)throws java.io.IOException {
  validate(files);var bytes=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
   for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
  }
  return bytes.toByteArray();
 }
}
