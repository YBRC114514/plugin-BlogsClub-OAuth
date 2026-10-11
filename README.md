# BlogsClub 账号验证

[![Halo](https://img.shields.io/badge/Halo-2.20.8%2B-blue)](https://www.halo.run)
[![License](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)
[![Halo 应用市场](https://img.shields.io/badge/Halo--App%20Store-待上架-orange)](https://www.halo.run/store)

让 Halo 站点支持使用 **BlogsClub（博客俱乐部）** 开放平台账号登录与绑定。

![logo](src/main/resources/logo.png)

## 截图

**登录页新增 BlogsClub 入口**

![登录页](screenshots/login-page.png)

**个人中心可绑定 / 解绑 BlogsClub 账号**

![身份认证](screenshots/identity-binding.png)

## 功能

- 登录页新增「使用 BlogsClub 登录」入口，基于 Halo 官方 `AuthProvider` / OAuth2 扩展点实现
- **首次登录自动建号**： BlogsClub 授权通过后自动创建 Halo 账号，并把 BlogsClub 回调返回的邮箱、昵称、头像一并写入，用户**无需填写或验证邮箱**
- 已绑定用户直接登录；个人中心可随时绑定 / 解绑 BlogsClub 账号
- AppID / AppKey 未配置时显示中文提示页，而不是抛出空白 500 错误
- 支持弹窗（popup）模式：`/oauth2/authorization/blogsclub?display=popup`

## 隐私与数据说明

本插件仅在你主动点击「使用 BlogsClub 登录」时，才会将浏览器跳转到 BlogsClub 开放平台完成授权。

- 插件向 BlogsClub 请求的scope：`basic,email`
- 从 BlogsClub 读取并保存到 Halo 用户资料的字段：`openid`（身份唯一标识）、`email`、`blog_name`（昵称）、`blogger_avatar`（头像）、`blog_url`
- **AppID / AppKey** 保存在 Halo 的认证方式配置中（AppKey 使用密码框，界面上不明文展示），仅用于服务端换取 `access_token`
- 插件**不会**将用户数据发送到除 `www.blogsclub.org` 以外的任何地址；不收集统计、不上报行为数据
- 卸载插件或解绑账号不会自动删除已创建的 Halo 用户

如果你不同意上述数据处理方式，请不要使用本插件。

## 兼容性

| 项目 | 要求 |
|------|------|
| Halo 版本 | `>=2.20.8` |
| Java | 21+ |
| BlogsClub | 需在开放平台创建并通过审核的应用 |

> `>=2.20.8` 是因为插件使用 Halo 2.20.8 引入的 `UserPreCreatingHandler` 扩展点在建号前写入用户资料。

## 安装

### 从应用市场安装（推荐）

在 Halo 后台「插件」→ 应用市场，搜索 **BlogsClub 账号验证** 并安装。

### 手动安装 JAR

1. Halo 后台「插件」→「上传插件」，选择 `plugin-blogsclub-1.0.0.jar`；
2. 安装完成后**启用**插件；
3. 进入「用户」→「认证方式」，找到 **BlogsClub** 并**启用**；
4. 点击该条目的**「设置」**，填写 **AppID** 与 **AppKey**（见下方）。

## 配置

### 1. 在 BlogsClub 开放平台创建应用

在 [BlogsClub 开放平台](https://www.blogsclub.org/platform.html) 创建应用并通过审核，获得 **AppID** 与 **AppKey**，并登记授权回调地址（必须与你的 Halo 外网地址完全一致）：

```
https://你的站点域名/login/oauth2/code/blogsclub
```

> `{baseUrl}` 取自 Halo 的 `halo.external-url` 配置，即你访问后台所用的地址。

### 2. 在 Halo 后台填写凭据

进入 **用户 → 认证方式 → BlogsClub → 设置**，填写：

| 字段 | 说明 |
|------|------|
| **AppID（Client ID）** | BlogsClub 开放平台应用详情页获取 |
| **AppKey（Client Secret）** | 同一应用对应的 AppKey，**以密文显示**，仅用于服务端换取令牌 |

两项均为必填。缺失时插件会 fail-closed（绝不以空凭据向 BlogsClub 发起请求），并提示你前往该配置页填写。

## 首次登录流程

1. 博主点击「使用 BlogsClub 登录」，跳转授权页并同意授权；
2. Halo 回调插件，插件用 AppID / AppKey 换取 `access_token`，并拉取博主资料；
3. 若该 `openid` **已绑定**本站账号 → 直接登录，跳转个人中心；
4. 若**未绑定** → Halo 自动创建账号，插件在建号前（`UserPreCreatingHandler`）把 BlogsClub 回调返回的 **邮箱 / 昵称 / 头像**写入新账号 —— 因为邮箱来自刚刚完成认证的身份提供方，Halo 视其为已提供，用户**不需要再做邮箱验证**；
5. 完成后自动登录并跳转个人中心。

之后博主既可一键 BlogsClub 登录，也可使用 Halo 的「忘记密码」功能自行设置站点密码。

## 排障

| 现象 | 原因与处理 |
|------|-----------|
| 点击登录提示「尚未完成 BlogsClub 配置」 | AppID / AppKey 未填写，去 **用户 → 认证方式 → BlogsClub → 设置** 填写 |
| BlogsClub 侧提示回调地址不匹配 | 登记的回调地址必须与 `halo.external-url` 完全一致，含协议与末尾无斜杠 |
| 日志出现 `ConfigMap/oauth2-blogsclub-config was not found` | 认证方式未启用或配置未保存，重新启用并保存一次 |
| 新账号邮箱为空 | 检查日志中 `[BlogsClub] 已缓存 BlogsClub 资料` 与 `[BlogsClub] 已自动写入 BlogsClub 邮箱` 两行是否出现 |
| BlogsClub 未开通 `email` 权限 | 授权页确认勾选了邮箱权限，否则新账号邮箱会为空 |

日志中的 `requestId` 可用于在 Halo 日志里定位同一次请求的完整堆栈。

## 可选：通过代理访问 BlogsClub

当 Halo 服务器无法直连 `www.blogsclub.org` 时，在 `application.yaml` 中配置：

```yaml
blogsclub:
  oauth2:
    proxy:
      enabled: true
      host: proxy.example.com
      port: 3128
      username: ""        # 可选
      password: ""        # 可选，建议用环境变量注入，勿提交到版本库
      connect-timeout-millis: 15000
```

出于安全考虑，`host` 仅接受纯主机名 / IP（不含协议、端口、路径）。

## 安全说明

- 所有对 BlogsClub 的请求经响应式 `WebClient` 发出，连接与响应超时均为 15s，响应体上限 256KB
- 目标地址固定为 `www.blogsclub.org`，不接受用户可控的目标覆写，以降低 SSRF 风险
- 回调失败或 AppKey 缺失时 **fail-closed**，不会以空密钥继续请求
- 凭据缺失时插件拒绝继续流程（fail-closed），不会静默降级

## 构建

```bash
./gradlew clean build
# 产物：build/libs/plugin-blogsclub-1.0.0.jar
```

运行单元测试：

```bash
./gradlew test
```

## 目录结构

```
src/main/java/run/halo/plugin/blogsclub/
  BlogsClubPlugin.java                        插件主类
  BlogsClubAuthenticationWebFilter.java       OAuth2 回调过滤器（含出站 WebClient 配置）
  BlogsClubRedirectWebFilter.java             授权跳转过滤器（透传 popup display 参数）
  BlogsClubOAuth2UserService.java             userinfo 适配器（解包 data / 映射 openid）
  BlogsClubClientRegistrationRepository.java  客户端注册仓库（读取 AppID / AppKey）
  BlogsClubUserPreCreatingHandler.java        建号前写入 BlogsClub 邮箱 / 昵称 / 头像
  BlogsClubOAuth2ClientRegistration.java      Scheme 定义
  DefaultOAuth2AuthorizedClientService.java   授权客户端持久化
  UserConnectionDisconnectedListener.java     解绑时清理授权客户端
src/main/resources/extensions/
  auth-provider.yaml                          认证方式定义
  client-registrations.yaml                   BlogsClub 端点与 scheme
  extension-definitions.yaml                  扩展点注册
  setting.yaml                                AppID / AppKey 配置表单
```

## 致谢

- [Halo](https://www.halo.run) —— 站点与插件运行平台
- [BlogsClub 开放平台](https://www.blogsclub.org/platform.html) —— 提供 OAuth 2.0 接口

## 许可证

[GPL-3.0](LICENSE)

## 反馈

问题反馈与功能建议请提交到 [GitHub Issues](https://github.com/YOUR_USERNAME/plugin-BlogsClub-OAuth/issues)。