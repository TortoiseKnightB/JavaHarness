package com.tinyclaw;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author TortoiseKnightB
 * @date 2026/05/22
 */
class ClawApplicationTest {

    @Test
    public void test(){
        Path path = Paths.get("./workspace/project_front").toAbsolutePath().normalize();
        String res = path.toString();
        System.out.println(res);
    }

}
