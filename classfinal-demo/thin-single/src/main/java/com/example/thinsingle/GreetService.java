package com.example.thinsingle;

import org.springframework.stereotype.Service;

/**
 * 方法体带分支/拼接，便于验证「解密是否真的生效」：
 * 若解密失败，方法体是空壳桩，会返回 null。
 */
@Service
public class GreetService {

    public String greet(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("hello-");
        sb.append(name.toUpperCase());
        sb.append("-");
        sb.append(1 + 2);
        return sb.toString();
    }
}
