package dev.atoms;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
 private final JdbcTemplate db;
 public HealthController(JdbcTemplate db){this.db=db;}
 @GetMapping("/api/health")
 public ResponseEntity<Map<String,String>> health(){
  try{
   db.queryForObject("SELECT 1",Integer.class);
   return ResponseEntity.ok(Map.of("status","UP"));
  }catch(org.springframework.dao.DataAccessException e){
   return ResponseEntity.status(503).body(Map.of("status","DOWN"));
  }
 }
}
