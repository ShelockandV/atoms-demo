package dev.atoms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ModelCatalog {
 private final Map<String,List<String>> models;
 private final Map<String,String> defaults;
 ModelCatalog(@Value("${atoms.model.name}") String build,
  @Value("${atoms.model.build-models:qwen-plus,qwen3-coder-plus,qwen3-coder-flash}") String builds,
  @Value("${atoms.model.chat-default:qwen-plus}") String chat,
  @Value("${atoms.model.chat-models:qwen-plus,qwen-flash}") String chats,
  @Value("${atoms.model.image.default-model:qwen-image-2.0}") String image,
  @Value("${atoms.model.image.models:qwen-image-2.0,qwen-image-2.0-pro}") String images) {
  defaults=Map.of("build",build,"chat",chat,"image",image);
  models=Map.of("build",list(builds,build),"chat",list(chats,chat),"image",list(images,image));
 }
 private List<String> list(String raw,String fallback) {var result=new LinkedHashSet<String>();result.add(fallback);for(String s:raw.split(","))if(!s.isBlank())result.add(s.trim());return List.copyOf(result);}
 String mode(String mode) {String value=mode==null?"build":mode;if(!models.containsKey(value))throw Store.error(400,"不支持的模式");return value;}
 String select(String mode,String requested) {String value=requested==null||requested.isBlank()?defaults.get(mode):requested;if(!models.get(mode).contains(value))throw Store.error(400,"该模式未配置此模型，请在 application.yml 中添加");return value;}
 Map<String,Object> view(){return Map.of("models",models,"defaults",defaults);}
}
