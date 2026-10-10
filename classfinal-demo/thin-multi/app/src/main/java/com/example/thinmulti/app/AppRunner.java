package com.example.thinmulti.app;

import com.example.thinmulti.lib.CalcService;
import org.springframework.stereotype.Service;

/**
 * 同时调用 app 自身代码与「外置的 lib jar」，用于验证外置依赖也能被加密并正常解密。
 */
@Service
public class AppRunner {

    private final CalcService calcService = new CalcService();

    public String go() {
        int v = calcService.add(2, 3);   // 期望 (2+3)*10 = 50
        return "calc=" + v + ",greet=" + "ab".toUpperCase();
    }
}
