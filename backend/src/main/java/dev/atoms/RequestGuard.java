package dev.atoms;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

@Component
public class RequestGuard extends OncePerRequestFilter {
 private final boolean secureCookie;
 public RequestGuard(@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
  this.secureCookie = secureCookie;
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
  res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Cache-Control","no-store");
  if(req.getRequestURI().startsWith("/api/")&&!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
   String origin=req.getHeader("Origin");String host=req.getHeader("Host");
   if(origin!=null) {
    try{var uri=URI.create(origin);if(!uri.getRawAuthority().equalsIgnoreCase(host)){res.sendError(403,"Origin mismatch");return;}}
    catch(IllegalArgumentException e){res.sendError(403);return;}
   }
   if(req.getContentLengthLong()>1_000_000){res.sendError(413);return;}
  }
  if(req.getRequestURI().startsWith("/api/")&&!req.getRequestURI().equals("/api/health")) {
   String visitor=null;
   if(req.getCookies()!=null)for(var cookie:req.getCookies())if(cookie.getName().equals("ATOMS_VISITOR")&&cookie.getValue().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))visitor=cookie.getValue();
   if(visitor==null)visitor=UUID.randomUUID().toString();
   var session=req.getSession();synchronized(session){session.setAttribute("owner",visitor);}
   var cookie=new Cookie("ATOMS_VISITOR",visitor);cookie.setHttpOnly(true);cookie.setSecure(req.isSecure()||secureCookie);cookie.setPath("/");cookie.setMaxAge(31536000);cookie.setAttribute("SameSite","Strict");res.addCookie(cookie);
  }
  chain.doFilter(req,res);
 }
}
