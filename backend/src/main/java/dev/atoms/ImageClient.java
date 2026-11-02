package dev.atoms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.List;

@Component
public class ImageClient {
 final ModelClient text;
 final ObjectMapper json;
 final String configuredEndpoint,key,size;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
 record Image(byte[] bytes,String mediaType){}
 ImageClient(ModelClient text,ObjectMapper json,
  @Value("${atoms.model.image.endpoint:}")String endpoint,@Value("${atoms.model.image.api-key:}")String key,
  @Value("${atoms.model.image.size:1024*1024}")String size){this.text=text;this.json=json;this.configuredEndpoint=endpoint;this.key=key;this.size=size;}
 URI endpoint(){
  if(!configuredEndpoint.isBlank()){
   ModelClient.endpoint(configuredEndpoint);
   URI uri=URI.create(configuredEndpoint.trim());
   if(uri.getPath().contains("/compatible-mode") || uri.getPath().endsWith("/chat/completions")) {
    throw new IllegalArgumentException("图像接口配置错误：atoms.model.image.endpoint 不能填写文本接口地址，请填写完整的 /api/v1/services/aigc/multimodal-generation/generation 地址，或留空自动推导");
   }
   return uri;
  }
  URI base=ModelClient.endpoint(text.base);
  String host=base.getHost();
  if(!host.endsWith(".aliyuncs.com"))throw new IllegalArgumentException("请配置 atoms.model.image.endpoint 为阿里云原生图像生成完整接口地址");
  return URI.create(base.getScheme()+"://"+base.getRawAuthority()+"/api/v1/services/aigc/multimodal-generation/generation");
 }
 Image generate(String prompt,String selectedModel)throws Exception {
  if(text.demo)return demo();
  String apiKey=key.isBlank()?text.key:key;if(apiKey.isBlank())throw new IllegalArgumentException("请配置图像模型 API Key 或文本模型 API Key");
  var payload=Map.of("model",selectedModel,"input",Map.of("messages",List.of(Map.of("role","user","content",List.of(Map.of("text",prompt))))),"parameters",Map.of("size",size,"n",1,"prompt_extend",true));
  var req=HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(240)).header("Content-Type","application/json").header("Authorization","Bearer "+apiKey).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
  var response=http.send(req,HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()/100!=2)throw new IllegalStateException(text.providerError(response.statusCode(),response.body()));
  if(response.body().length()>1_000_000)throw new IllegalStateException("图像服务响应过大");
  var root=json.readTree(response.body());String url="";
  for(var item:root.path("output").path("choices").path(0).path("message").path("content"))if(item.path("image").isTextual()){url=item.get("image").asText();break;}
  if(url.isBlank())throw new IllegalStateException("图像服务未返回图片，请检查图像模型和接口类型是否匹配");
  // Fetch only provider-owned OSS results. Never forward the API key to storage.
  URI uri=URI.create(url);String host=uri.getHost();
  if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!host.endsWith(".aliyuncs.com")||uri.getUserInfo()!=null)throw new IllegalStateException("图像服务返回了不受支持的下载域名");
  var download=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
  try(var stream=download.body()){
   if(download.statusCode()!=200)throw new IllegalStateException("图片已生成，但下载失败；请稍后重试");
   byte[] bytes=stream.readNBytes(8_000_001);if(bytes.length>8_000_000)throw new IllegalStateException("生成图片超过8MB，请降低图片尺寸");
   String type=download.headers().firstValue("Content-Type").orElse("").split(";")[0];
   if(!Set.of("image/png","image/jpeg","image/webp").contains(type))throw new IllegalStateException("图片格式不受支持");
   return new Image(bytes,type);
  }
 }
 Image demo()throws IOException {
  var image=new BufferedImage(768,768,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
  try{g.setPaint(new GradientPaint(0,0,new Color(97,86,220),768,768,new Color(244,178,153)));g.fillRect(0,0,768,768);g.setColor(new Color(255,255,255,65));g.fillOval(190,150,460,460);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,45));g.drawString("Atoms Image Demo",125,370);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,20));g.drawString("Offline sample - not AI generated",200,420);}finally{g.dispose();}
  var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return new Image(out.toByteArray(),"image/png");
 }
}
