package com.lanou.csh;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.sql.Types;
import java.util.Collections;

public class CodeGenerator {
    public static void main(String[] args) {
        // 数据库连接信息
        String url = "jdbc:mysql:///campus_second_hand";
        String username = "root";
        String password = "123456";

        FastAutoGenerator.create(url, username, password)
                // 全局配置
                .globalConfig(builder -> {
                    builder.author("lanou")
                            .outputDir("src\\main\\java");
                })
                // 数据源类型转换处理
                .dataSourceConfig(builder -> builder.typeConvertHandler((globalConfig, typeRegistry, metaInfo) -> {
                    int typeCode = metaInfo.getJdbcType().TYPE_CODE;
                    if (typeCode == Types.SMALLINT) {
                        return DbColumnType.INTEGER;
                    }
                    return typeRegistry.getColumnType(metaInfo);
                }))
                // 包配置
                .packageConfig(builder -> builder
                        .parent("com.lanou.csh")
                        .pathInfo(Collections.singletonMap(OutputFile.xml, "src\\main\\resources\\mapper"))
                )
                // 策略配置
                .strategyConfig(builder -> builder
                        // 需要生成的数据表
                        .addInclude("banner","category","favorite","product","product_image","user")
                        // 过滤表前缀
                        .addTablePrefix("t_", "c_")
                        // 实体开启lombok
                        .entityBuilder().enableLombok()
                        // Controller 使用 @RestController
                        .controllerBuilder().enableRestStyle()
                        // Service 接口去除I前缀
                        .serviceBuilder().convertServiceFileName(entityName -> entityName + "Service")
                        // Mapper 添加@Mapper注解
                        .mapperBuilder().mapperAnnotation(org.apache.ibatis.annotations.Mapper.class)
                )
                // 使用Freemarker模板引擎
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();
    }
}