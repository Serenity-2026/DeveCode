package com.agent;
//signature 是 Anthropic extended thinking 的验证签名，用来防止篡改思考内容。
public record ThinkingBlock(String thinking, String signature) {
}
