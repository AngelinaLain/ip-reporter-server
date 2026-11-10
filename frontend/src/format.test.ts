import { describe, expect, it } from "vitest";
import { fullDomain, relativeTime, shortAddress, statusTone } from "./format";
describe("控制台展示", () => {
  it("解析根记录与子域名", () => {
    expect(fullDomain("@", "example.com")).toBe("example.com");
    expect(fullDomain("home", "example.com")).toBe("home.example.com");
  });
  it("使用接收时间显示在线间隔", () => {
    expect(
      relativeTime("2026-11-10T00:00:00Z", Date.parse("2026-11-10T00:05:00Z")),
    ).toBe("5 分钟前");
    expect(relativeTime(undefined)).toBe("尚未上报");
  });
  it("保留地址完整值并区分同步失败", () => {
    expect(shortAddress("192.168.1.10")).toBe("192.168.1.10");
    expect(statusTone("ERROR")).toBe("red");
    expect(statusTone("SUCCESS")).toBe("green");
  });
});
