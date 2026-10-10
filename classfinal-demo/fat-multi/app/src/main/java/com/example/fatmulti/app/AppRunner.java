package com.example.fatmulti.app;

import com.example.fatmulti.lib.CalcService;
import org.springframework.stereotype.Service;

/**
 * 同时用到 app 自身代码与 lib 模块代码，用于验证两者都被加密后仍能正常运行。
 */
@Service
public class AppRunner {

    private final CalcService calcService = new CalcService();

    public String go() {
        int v = calcService.add(2, 3);   // 期望 (2+3)*10 = 50
        return "calc=" + v + ",greet=" + "ab".toUpperCase();
    }
}
