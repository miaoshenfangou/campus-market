package com.lanou.csh;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.lanou.csh.mapper")
public class CshApplication {
    public static void main(String[] args) {
        SpringApplication.run(CshApplication.class, args);
    }
}