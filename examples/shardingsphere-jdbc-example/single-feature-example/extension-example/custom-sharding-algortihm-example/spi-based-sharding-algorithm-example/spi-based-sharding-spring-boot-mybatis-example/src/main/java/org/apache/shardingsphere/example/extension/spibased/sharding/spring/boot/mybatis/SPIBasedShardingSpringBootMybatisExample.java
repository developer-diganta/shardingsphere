/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.example.extension.spibased.sharding.spring.boot.mybatis;

import org.apache.shardingsphere.example.core.api.ExampleExecuteTemplate;
import org.apache.shardingsphere.example.core.api.service.ExampleService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

import java.sql.SQLException;

@ComponentScan("org.apache.shardingsphere.example.core.mybatis")
@MapperScan(basePackages = "org.apache.shardingsphere.example.core.mybatis.repository")
@SpringBootApplication
public class SPIBasedShardingSpringBootMybatisExample {

    public static void main(String[] args) throws SQLException {
        try (ConfigurableApplicationContext applicationContext = SpringApplication.run(SPIBasedShardingSpringBootMybatisExample.class, args)) {
            ExampleExecuteTemplate.run(applicationContext.getBean(ExampleService.class));
        }
    }
}
