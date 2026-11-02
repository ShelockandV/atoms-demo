package dev.atoms;
import java.util.*;

/** Explicit offline samples; not presented as AI generation. */
class DemoTemplates {
 static Map<String,String> create(String prompt,Map<String,String> current){
  if(prompt.matches("(?is).*(java(?!script)|spring\\s*boot|后端|backend).*"))return javaProject();
  if(!current.isEmpty()&&!SourceFiles.canPreview(current))return new LinkedHashMap<>(current);
  if(!current.isEmpty()) {
   var result=new HashMap<>(current);
   if(prompt.contains("暗")||prompt.toLowerCase().contains("dark"))result.put("style.css",result.get("style.css")+"\nbody{background:#151621;color:#f4f4fa}main,.card,form{background:#202231!important;color:#f4f4fa}input,select{background:#292c3c;color:white;border-color:#444}");
   else if(prompt.contains("绿"))result.put("style.css",result.get("style.css").replace("#6262f5","#14876c"));
   else if(prompt.contains("蓝"))result.put("style.css",result.get("style.css").replace("#6262f5","#2476e8"));
   else return result;
   return result;
  }
  boolean tasks=prompt.contains("任务")||prompt.toLowerCase().contains("task");
  boolean money=prompt.contains("账")||prompt.contains("支出")||prompt.toLowerCase().contains("expense");
  String html,js;
  if(money){
   html="<header><span class='logo'>◈ pocket</span><span>让每一笔花费，都心中有数</span></header><main><div class='eyebrow'>YOUR DAILY FINANCES</div><h1>小账本，大生活。</h1><p>记录日常，留住生活的小确幸。</p><section class='stats'><div class='card'><small>本月支出</small><h2 id='total'>¥ 128.00</h2><small>从一笔简单的记录开始</small></div><div class='card'><small>记录笔数</small><h2 id='count'>2</h2><small>每一笔都值得被看见</small></div></section><form id='form'><input id='name' placeholder='这笔钱花在了哪里？' required maxlength='60'><input id='amount' type='number' min='0.01' max='1000000' step='0.01' placeholder='金额 ¥' required><button>＋ 记一笔</button></form><h3>最近记录</h3><div id='list'></div></main><footer>Less worrying. More living.</footer>";
   js="var items=[{name:'周末的一杯咖啡',amount:28},{name:'超市采购',amount:100}];function render(){var list=document.getElementById('list');list.replaceChildren();items.forEach(function(x,i){var row=document.createElement('div');row.className='row';var name=document.createElement('span');name.textContent=x.name;var amount=document.createElement('strong');amount.textContent='¥ '+x.amount.toFixed(2);var del=document.createElement('button');del.className='delete';del.textContent='删除';del.addEventListener('click',function(){items.splice(i,1);render()});row.append(name,amount,del);list.append(row)});document.getElementById('total').textContent='¥ '+items.reduce(function(s,x){return s+x.amount},0).toFixed(2);document.getElementById('count').textContent=items.length}document.getElementById('form').addEventListener('submit',function(e){e.preventDefault();var name=document.getElementById('name');var amount=document.getElementById('amount');var n=Number(amount.value);if(!name.value.trim()||!Number.isFinite(n)||n<=0)return;items.unshift({name:name.value.trim(),amount:n});e.target.reset();render()});render();";
  }else if(tasks){
   html="<header><span class='logo'>◈ focus</span><span>为重要的事，留出空间</span></header><main><div class='eyebrow'>MAKE ROOM FOR WHAT MATTERS</div><h1>今天，好好专注。</h1><p>清空脑海里的待办，从一件小事开始。</p><section class='stats'><div class='card'><small>今日任务</small><h2 id='total'>0</h2></div><div class='card'><small>已完成</small><h2 id='count'>0</h2></div></section><form id='form'><input id='name' placeholder='添加一个想完成的小目标' required maxlength='100'><button>＋ 添加任务</button></form><div id='list'></div></main><footer>One thing at a time.</footer>";
   js="var items=[{name:'梳理本周的产品想法',done:false},{name:'读20分钟喜欢的书',done:true},{name:'给自己泡一杯咖啡',done:false}];function render(){var list=document.getElementById('list');list.replaceChildren();items.forEach(function(x,i){var row=document.createElement('div');row.className='row';var check=document.createElement('input');check.type='checkbox';check.checked=x.done;check.addEventListener('change',function(){x.done=check.checked;render()});var text=document.createElement('span');text.textContent=x.name;text.style.textDecoration=x.done?'line-through':'none';var del=document.createElement('button');del.className='delete';del.textContent='删除';del.addEventListener('click',function(){items.splice(i,1);render()});row.append(check,text,del);list.append(row)});document.getElementById('total').textContent=items.length;document.getElementById('count').textContent=items.filter(function(x){return x.done}).length}document.getElementById('form').addEventListener('submit',function(e){e.preventDefault();var input=document.getElementById('name');if(!input.value.trim())return;items.unshift({name:input.value.trim(),done:false});input.value='';render()});render();";
  }else{
   html="<header><span class='logo'>◈ studio.</span><span>独立思考 · 自由创造</span></header><main class='landing'><div class='eyebrow'>A LITTLE IDEA. A BIG POSSIBILITY.</div><h1>把灵感，<br>变成下一个可能。</h1><p>为有想法的人，打造简单、美好、值得使用的产品。<br>从今天开始，让创造发生。</p><button id='start'>开启你的旅程 ↗</button><section class='stats'><div class='card'><h2>01</h2><h3>保持好奇</h3><p>好问题，是一切创造的起点。</p></div><div class='card'><h2>02</h2><h3>大胆尝试</h3><p>从小小的一步，走向真实的改变。</p></div><div class='card'><h2>03</h2><h3>持续生长</h3><p>让每一次迭代，都更接近你想要的样子。</p></div></section><div id='notice' role='status'></div></main><footer>Made for the things you haven't made yet.</footer>";
   js="document.getElementById('start').addEventListener('click',function(){document.getElementById('notice').textContent='欢迎开始！留下你的下一个想法，让它成为现实。';document.getElementById('notice').scrollIntoView({behavior:'smooth'})});";
  }
  String css="*{box-sizing:border-box}body{margin:0;background:#faf9f6;color:#242528;font-family:system-ui,-apple-system,sans-serif}header{padding:24px 6%;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #e9e7e2;font-size:12px;color:#8b8b91}.logo{font-size:24px;font-weight:700;color:#34343b}main{max-width:900px;margin:60px auto;padding:0 28px}.eyebrow{font-size:10px;color:#6262f5;letter-spacing:2px;font-weight:700}h1{font-size:44px;letter-spacing:-2px;margin:16px 0}p{color:#8b8b91;line-height:1.9;font-size:14px}.stats{display:flex;gap:16px;margin:36px 0}.card{background:white;border:1px solid #eae8e4;border-radius:16px;padding:24px;flex:1}small{color:#8b8b91}h2{font-size:32px;margin:16px 0}form{display:flex;gap:10px;padding:16px;background:#fff;border:1px solid #eae8e4;border-radius:14px;margin:30px 0}input{border:1px solid #e5e5ea;border-radius:8px;padding:12px;font:inherit;min-width:0}input:first-child{flex:1}input[type=number]{width:130px}input[type=checkbox]{flex:0;width:18px;accent-color:#6262f5}button{border:0;background:#6262f5;color:white;border-radius:8px;padding:12px 20px;cursor:pointer;font:inherit;font-size:13px}.row{display:flex;align-items:center;gap:14px;padding:20px 4px;border-bottom:1px solid #e9e7e2;font-size:14px}.row span{flex:1}.delete{background:transparent;color:#92929a;padding:6px}footer{text-align:center;padding:32px;color:#aaa;font-size:11px}.landing{text-align:center}.landing h1{font-size:64px}.landing .stats{text-align:left;margin-top:64px}#notice{padding:20px;color:#6262f5}@media(max-width:600px){main{margin:32px auto}h1,.landing h1{font-size:34px}.stats{flex-wrap:wrap}.card{min-width:150px}form{flex-wrap:wrap}header span:last-child{display:none}}";
  return Map.of("index.html","<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='style.css'></head><body>"+html+"<script src='script.js'></script></body></html>","style.css",css,"script.js",js);
 }
 static Map<String,String> javaProject(){
  var files=new LinkedHashMap<String,String>();
  files.put("pom.xml", """
   <project xmlns="http://maven.apache.org/POM/4.0.0">
     <modelVersion>4.0.0</modelVersion>
     <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.7</version><relativePath/></parent>
     <groupId>com.example</groupId><artifactId>hello-api</artifactId><version>0.1.0</version>
     <properties><java.version>21</java.version></properties>
     <dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
     <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
   </project>
   """);
  files.put("src/main/java/com/example/HelloApplication.java", """
   package com.example;
   import org.springframework.boot.SpringApplication;
   import org.springframework.boot.autoconfigure.SpringBootApplication;
   @SpringBootApplication
   public class HelloApplication {
     public static void main(String[] args) { SpringApplication.run(HelloApplication.class,args); }
   }
   """);
  files.put("src/main/java/com/example/HelloController.java", """
   package com.example;
   import java.util.Map;
   import org.springframework.web.bind.annotation.GetMapping;
   import org.springframework.web.bind.annotation.RestController;
   @RestController
   public class HelloController {
     @GetMapping("/hello")
     public Map<String,String> hello() { return Map.of("message","Hello World"); }
   }
   """);
  files.put("src/main/resources/application.yml", "server:\n  port: 8081\n");
  files.put("README.md", "# Java Hello API\n\n离线演示模板，不代表真实模型生成。需要 Java 21 和 Maven。\n\n运行：`mvn spring-boot:run`，访问 http://localhost:8081/hello 。\n");
  return files;
 }

}
