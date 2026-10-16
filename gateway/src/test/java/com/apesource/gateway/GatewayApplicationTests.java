package com.apesource.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Scanner;


class GatewayApplicationTests {

    @Test
    void contextLoads() {
        Scanner sc = new Scanner(System.in);
        System.out.println("请输入：");
        String str = sc.nextLine();
        System.out.println("输入的内容是：" + str);
    }

}
