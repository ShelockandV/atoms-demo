package dev.atoms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class ModelClient {
 final boolean demo;
 final String base,key,model;
 final int timeoutSeconds,maxOutputTokens;
 final ObjectMapper json;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
 ModelClient(ObjectMapper json,@Value("${atoms.demo}") boolean demo,@Value("${atoms.model.base-url}") String base,@Value("${atoms.model.api-key}") String key,@Value("${atoms.model.name}") String model,@Value("${atoms.model.timeout-seconds:300}") int timeoutSeconds,@Value("${atoms.model.max-output-tokens:16000}") int maxOutputTokens) {this.json=json;this.demo=demo;this.base=base;this.key=key;this.model=model;if(timeoutSeconds<1||timeoutSeconds>1800)throw new IllegalArgumentException("atoms.model.timeout-seconds 须为1～1800秒");if(maxOutputTokens<1||maxOutputTokens>65536)throw new IllegalArgumentException("atoms.model.max-output-tokens 须为1～65536");this.timeoutSeconds=timeoutSeconds;this.maxOutputTokens=maxOutputTokens;}
 static URI endpoint(String base) {
  String hint = "模型地址配置错误：请在 后端私有配置中的 atoms.model.base-url 中填写完整的 http:// 或 https:// 地址及供应商 API 路径前缀";
  if (base == null || base.isBlank()) throw new IllegalArgumentException(hint);
  try {
   URI uri = URI.create(base.trim());
   if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
       || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
    throw new IllegalArgumentException(hint);
   }
   String normalized = uri.toString().replaceAll("/+$", "");
   return URI.create(normalized.endsWith("/chat/completions") ? normalized : normalized + "/chat/completions");
  } catch (IllegalArgumentException e) {
   throw new IllegalArgumentException(hint);
  }
 }
 String call(String system,String user,String selectedModel) throws Exception {
  return completion(List.of(Map.of("role","system","content",system),Map.of("role","user","content",user)),selectedModel);
 }
 String completion(List<Map<String,String>> messages,String selectedModel) throws Exception {
  if(key.isBlank()||model.isBlank()) throw new IllegalArgumentException("请在后端私有配置中配置 atoms.model.api-key 和 atoms.model.name，或设置 atoms.demo=true");
  URI endpoint=endpoint(base);
  String body=json.writeValueAsString(Map.of("model",selectedModel,"messages",messages,"max_tokens",maxOutputTokens));
  var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
  var response=http.send(request,HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()/100!=2) throw new IllegalStateException(providerError(response.statusCode(), response.body()));
  if(response.body().length()>2_000_000) throw new IllegalArgumentException("模型响应过大");
  com.fasterxml.jackson.databind.JsonNode root;
  try{root=json.readTree(response.body());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("模型服务响应不是合法JSON，请检查接口是否返回网关错误页面");}
  if(root==null)throw new IllegalStateException("模型服务返回空响应");
  var choice=root.path("choices").path(0);
  if("length".equals(choice.path("finish_reason").asText()))throw new IllegalStateException("模型输出达到长度上限，源码可能不完整。请缩小本次生成范围，或调整 atoms.model.max-output-tokens 后重试");
  var content=choice.path("message").path("content");
  if(!content.isTextual()||content.asText().isBlank()) throw new IllegalArgumentException("模型返回空内容");return content.asText();
 }
 String providerError(int status, String body) {
  // Display known provider codes without exposing raw responses or credentials.
  String code = "";
  if (body.length() <= 100_000) {
   try {
    var root = json.readTree(body);
    code = root.path("error").path("code").asText("");
    if (code.isBlank()) code = root.path("code").asText("");
   } catch (Exception ignored) { }
  }
  if ("AccessDenied.Unpurchased".equals(code)) {
   return "模型访问被拒绝（HTTP " + status + "，AccessDenied.Unpurchased）：供应商提示服务尚未开通，请使用阿里云主账号检查当前地域的百炼服务开通状态；若已开通，再核对 API Key 所属工作空间和模型授权";
  }
  return switch (status) {
   case 401 -> "模型认证失败（HTTP 401）：请检查 API Key 是否有效，以及是否属于当前地域与工作空间";
   case 403 -> "模型访问被拒绝（HTTP 403）：请检查账号及工作空间的模型授权与服务开通状态";
   case 404 -> "模型接口或模型不存在（HTTP 404）：请检查 API 路径前缀和模型 ID";
   case 429 -> "模型请求受限（HTTP 429）：请检查调用频率、额度与并发限制，稍后再试";
   default -> "模型服务返回 HTTP " + status + "，请检查供应商服务状态及配置";
  };
 }
 String projectCall(String system,String prompt,ProjectContext context,String details,String selectedModel) throws Exception {
  var messages=new ArrayList<Map<String,String>>();
  messages.add(Map.of("role","system","content",system+"\n你正在同一项目中连续协作。结合项目历史理解指代、需求和已讨论的问题；本轮明确要求优先。当前源码是本轮修改的唯一基准，可能来自手动编辑或历史版本恢复，不要用历史描述覆盖它。修复问题时保留无关功能、样式和文件，除非用户明确要求替换。"));
  messages.addAll(context.history());
  String content="项目名称："+context.title()+"\n当前基准版本："+Objects.requireNonNullElse(context.baseVersion(),"尚无源码版本")+"\n当前源码（完整快照）："+json.writeValueAsString(context.files())+"\n"+details+"\n本次需求："+prompt;
  messages.add(Map.of("role","user","content",content));
  return completion(messages,selectedModel);
 }
 String chat(String prompt,ProjectContext context,String selectedModel) throws Exception {
  if(demo)return "【演示回答】已收到你的问题："+prompt+"。当前为离线演示；配置真实模型后可进行多轮问答。对话不会修改应用代码或创建版本。";
  return projectCall("你是友好的中文助手。根据项目上下文直接回答问题，可以讨论需求、解释当前代码和提供建议。对话模式只回答，不修改源码；需要实际修复时提醒用户切换构建模式继续提出修改。",prompt,context,"",selectedModel);
 }
 String plan(String prompt,ProjectContext context,String selectedModel) throws Exception {
  return demo?"演示模式：使用内置模板展示流程；支持记账、任务看板、落地页及Java后端示例。任意自然语言修改需要配置真实模型。":projectCall("你是一位软件工程师。先判断用户要求的是前端页面还是后端工程，遵循明确指定的语言与框架。前端预览运行在隔离iframe中，计划不得使用eval、Function构造器、localStorage或cookie；计算器使用普通函数实现运算。用中文简述技术栈、源码目录、核心能力和实现步骤，最多250字。后端工程应包含构建配置与启动说明。",prompt,context,"",selectedModel);
 }
 Map<String,String> generate(String prompt,String plan,ProjectContext context,String failure,String selectedModel) throws Exception {
  if(demo) return DemoTemplates.create(prompt,context.files());
  String output=projectCall("""
   你是专业软件工程师。严格根据用户要求的语言和技术栈生成真实工程源码。
   只输出一个JSON对象：每个键是文件相对路径，每个值是该文件完整文本内容。不要包装files字段，不要Markdown围栏，不要把源码嵌入HTML展示，不要省略为伪代码或省略号。
   最多80个文件，总量小于1MB。使用正斜杠目录，禁止绝对路径及..。返回完整版本快照，不是补丁。
   如果用户要求Java/Spring Boot等后端，生成pom.xml、src/main/java/...源文件、配置文件和README.md启动说明。其他后端语言也按其实际工程结构输出。不要附带无关的静态三文件模板。使用占位配置，不生成真实密钥。
   如果用户要求可在浏览器直接体验的前端页面，默认输出index.html、style.css、script.js（可附README.md）。只用原生HTML/CSS/JavaScript，无外部依赖、网络请求、iframe。禁止eval和Function构造器（含new Function）；计算器须用普通函数实现解析与运算。HTML有body，引用style.css和script.js；使用addEventListener，禁止内联事件。数据仅在内存保存，不用localStorage和cookie，制作美观且可交互的页面。
   当前需求明确切换技术栈时，移除旧版不相关文件；否则修改时保留原有功能与未改动文件。后端代码只用于源码导出，不会在工作台执行。
   """, prompt,context,"本轮计划："+plan+"\n本轮上次校验错误："+failure,selectedModel);
  output=output.trim(); if(output.startsWith("```")) output=output.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
  com.fasterxml.jackson.databind.JsonNode node;
  try{node=json.readTree(output);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("模型未返回合法的源码文件JSON，请按文件路径与完整内容重新输出");}
  if(node==null||!node.isObject()||node.isEmpty()||node.size()>80)throw new IllegalArgumentException("生成结果须为包含1～80个源码文件的JSON对象");
  Map<String,String> result=new LinkedHashMap<>();
  var fields=node.fields();while(fields.hasNext()){var field=fields.next();if(!field.getValue().isTextual())throw new IllegalArgumentException("每个文件内容必须是字符串："+field.getKey());result.put(field.getKey(),field.getValue().asText());}
  return result;
 }
}
