# DDNS 服务商与权限

管理页面支持阿里云 DNS、腾讯云 DNSPod 和 Cloudflare。填写凭据后，创建规则并选择机器、网卡与 A / AAAA 地址。规则保存为停用时不会由定时任务修改 DNS；手动同步和启用的具体行为以管理页面和服务端操作说明为准。

## 配置字段

| 服务商 | Access Key ID 字段 | Secret 字段 | 主域名 | 默认线路 |
| --- | --- | --- | --- | --- |
| 阿里云 `ALIYUN` | RAM AccessKey ID | RAM AccessKey Secret | `example.com` | `default` |
| 腾讯云 `TENCENT` | CAM SecretId | CAM SecretKey | `example.com` | `默认` |
| Cloudflare `CLOUDFLARE` | Zone ID | API Token | Zone 对应域名，如 `example.com` | 不使用线路 |

主机记录填写 `home`，最终记录为 `home.example.com`；填写 `@` 代表主域名本身。一个规则只管理一种类型：IPv4 使用 `A`，IPv6 使用 `AAAA`。TTL 范围为 60..86400 秒，实际最小值还受服务商套餐限制；云 API 拒绝 TTL 时调整规则，不会自动降低限制或修改其他解析。

密钥存入数据库前由应用的 AES-256-GCM 主密钥加密。请持久保存 `APP_ENCRYPTION_KEY`；丢失主密钥后无法恢复已保存凭据。前端 API 不返回密钥明文，SDK 请求签名和原始错误正文不写入同步日志。

## 阿里云

使用 RAM 子账号，给要管理的域名授权以下操作：

- `alidns:DescribeDomainRecords`
- `alidns:AddDomainRecord`
- `alidns:UpdateDomainRecord`

适配器使用官方 Java SDK、HTTPS `alidns.aliyuncs.com`，按页查询后再次校验主域名、主机记录、类型与线路。资源范围可参考[查询记录的授权信息](https://help.aliyun.com/zh/dns/api-alidns-2015-01-09-describedomainrecords)和[修改记录的授权信息](https://help.aliyun.com/zh/dns/api-alidns-2015-01-09-updatedomainrecord)，将 RAM 策略资源限制到需要的域名。

### REQUEST_FAILED 与诊断

服务端 1.0.0 / 1.0.1 使用空的 `HttpClientConfig` 构造 SDK，HTTP 实现类型未初始化，导致请求尚未发出就被转换成通用 `REQUEST_FAILED`。这条错误不能证明阿里云账号权限不足。1.0.2 改用 SDK 默认配置工厂，保留 HTTPS、证书校验和原超时，并增加真实客户端构造及关闭回归，测试不需要网络或云凭据。

升级时保留 `.env` 中的密钥与现有规则，将 `IMAGE_TAG` 改为 `1.0.2` 后执行 `docker compose pull` 和 `docker compose up -d`。规则已启用时会在下一轮重试，也可在管理页手动同步；此修复不需要重新填写 AccessKey。

新版错误包含失败动作（初始化、查询、创建或更新）、安全的 SDK 错误码及可用的 RequestId。初始化失败会显示 `SDK_INITIALIZATION_FAILED`；DNS 解析、连接、超时和 TLS 故障会分别提示检查对应的服务端网络环节。只有云端返回鉴权相关错误码时才提示核对对应授权。SDK 原始异常正文、签名 URL 和凭据不会写入同步日志。

## 腾讯云 DNSPod

这是腾讯云 API 3.0 DNSPod 接口，使用 **SecretId / SecretKey**；传统 DNSPod 的 `ID,Token` 不适用于此适配器。

CAM 子账号需要对应域名的以下操作：

- `dnspod:DescribeRecordList`
- `dnspod:CreateRecord`
- `dnspod:ModifyRecord`

使用官方 Java SDK 和 TC3-HMAC-SHA256 签名，请求固定的 HTTPS `dnspod.tencentcloudapi.com`；默认线路为 `默认`。地域参数无需填写。参见[腾讯云查询解析记录列表](https://cloud.tencent.com/document/api/1427/56166)和[腾讯云 Java SDK](https://github.com/TencentCloud/tencentcloud-sdk-java)。

## Cloudflare

创建只作用于所选 Zone 的 API Token，授权 **Zone Read** 和 **DNS Edit**（包含记录读取能力；若令牌策略将读取单独列出，一并授予 DNS Read）。Zone ID 可在域名的 Overview 页面获得。

适配器先读取 Zone，校验它与规则主域名一致；随后仅对对应名称和类型的记录进行查询、创建或 PATCH。创建的记录为 **DNS only**。已有记录如果启用了 Cloudflare 代理，适配器会提示先在服务商关闭代理，不会自动更改代理开关。PATCH 保留原有备注、标签等字段。参见[Cloudflare DNS API](https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/list/)和[更新记录](https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/edit/)。

## 同步规则

- 每次先查询远端当前值，只有地址或 TTL 变化才写入；IPv6 的等价缩写不会触发无意义更新。
- 存在多个相同域名、主机记录、类型与线路的记录时，必须填写明确的记录 ID，防止修改负载均衡记录。指定 ID 必须属于所选记录范围；不存在时返回错误，不会改动其他记录。
- 已暂停的阿里云 / 腾讯云记录必须先在云控制台启用。程序不会擅自启用其他服务暂停的解析。
- 不提供删除 DNS 记录的操作。客户端断网、没有对应地址、机器过期或规则被移除时，程序保留远端原记录。
- 定时检查会校验远端值，因此外部修改会在下次同步恢复到规则选中的地址。请避免其他 DDNS 程序同时管理同一条记录。
- 云 API 分页设置安全上限并设连接/读取超时；失败由后续同步重试，不在 SDK 内无限重试。服务端出口必须能访问上述 HTTPS API 与系统 CA。

本项目的服务商测试使用 SDK / HTTP 传输替身验证参数、分页、记录归属、歧义处理、更新条件及脱敏错误；没有在本次构建中使用真实云凭据或修改真实 DNS。正式启用前可先为测试子域名创建单条规则，确认控制台同步记录和权威解析结果。
