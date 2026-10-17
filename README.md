# BlogsClub 账号验证

[![License](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)

让 Halo 站点支持使用 **BlogsClub（博客俱乐部）** 开放平台账号登录与绑定。

![logo](src/main/resources/logo.png)

## 截图

**登录页新增 BlogsClub 入口**

![登录页](screenshots/login-page.png)

**个人中心可绑定 / 解绑 BlogsClub 账号**

![身份认证](screenshots/identity-binding.png)

## 功能

- 登录页新增「使用 BlogsClub 登录」入口，基于 Halo 官方 `AuthProvider` / OAuth2 扩展点实现
- **首次登录自动建号**： BlogsClub 授权通过后，在站点「允许注册」开启且已配置默认角色时自动创建 Halo 账号，并把 BlogsClub 回调返回的邮箱、昵称、头像一并写入，用户**无需填写或验证邮箱**；任一条件不满足则不建号，转入 Halo 原生绑定流程
- 已绑定用户直接登录；个人中心可随时绑定 / 解绑 BlogsClub 账号
- AppID / AppKey 未配置时显示中文提示页，而不是抛出空白 500 错误
- 支持弹窗（popup）模式：`/oauth2/authorization/blogsclub?display=popup`

## 隐私与数据说明

本插件仅在你主动点击「使用 BlogsClub 登录」时，才会将浏览器跳转到 BlogsClub 开放平台完成授权。

- 插件向 BlogsClub 请求的scope：`basic,email`
- 从 BlogsClub 读取并保存到 Halo 用户资料的字段：`openid`（身份唯一标识）、`email`、`blog_name`（昵称）、`blogger_avatar`（头像）
- BlogsClub 返回的 `blog_url`（博主主页地址）插件**不读取、不存储**，仅在此说明接口中存在该字段
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

> `>=2.20.8` 是因为自动建号链路使用了 Halo 2.20.8 引入的 `UserPreCreatingHandler` 扩展点在建号前写入用户资料。
>
> 注意：站点设置里的「必须验证邮箱」开关只作用于 Halo 自助注册表单。第三方 OAuth2 授权建号走的是 `UserService#createUser`，不经过该校验 —— 这是 Halo 官方设计，插件不会、也无法绕过它。

## 安装

### 从应用市场安装（推荐）

在 Halo 后台「插件」→ 应用市场，搜索 **BlogsClub 账号验证** 并安装。

### 手动安装 JAR

1. Halo 后台「插件」→「上传插件」，选择 `plugin-blogsclub-1.3.0.jar`；
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

> **AppKey 的存放位置**：保存后插件会把 AppKey 转入 Halo 密钥（Secret，名称 `blogsclub-oauth2-client-secret`）中保管，不再留存于 ConfigMap——密钥类凭据按 Halo 插件开发规范应存放于 Secret。若在控制台重新保存本表单，插件会在下次读取时自动再次完成该迁移。

## 首次登录流程

1. 博主点击「使用 BlogsClub 登录」，跳转授权页并同意授权；
2. Halo 回调插件，插件用 AppID / AppKey 换取 `access_token`，并拉取博主资料；
3. 若该 `openid` **已绑定**本站账号 → 直接登录，跳转个人中心；
4. 若**未绑定** → 站点设置允许注册且存在默认角色时，插件创建 Halo 账号，并在建号前（`UserPreCreatingHandler`）把 BlogsClub 回调返回的 **邮箱 / 昵称 / 头像**写入新账号。资料按建号用户名严格关联，只会写入这一次创建的账号；邮箱直接来自刚刚完成认证的身份提供方，因此视为已提供、**无需再做邮箱验证**；
5. 若站点关闭了「允许注册」或未配置默认角色 → 插件**不创建账号**，转而跳转到 Halo 原生绑定流程（`/login?oauth2_bind`），需先手动注册或登录本站账号再完成绑定；
6. 完成后自动登录并跳转个人中心。

> 第 6 步的登录态由 Halo 内核的 OAuth2 映射过滤器完成：插件在回调中写好账号与绑定关系后，内核会把认证结果映射到真实账号上，并统一处理记住登录、设备记录等登录增强逻辑。

之后博主既可一键 BlogsClub 登录。由于账号是自动创建、没有设置过密码，若需要使用密码登录可在个人中心自行设置站点密码。

## 排障

| 现象 | 原因与处理 |
|------|-----------|
| 点击登录提示「尚未完成 BlogsClub 配置」 | AppID / AppKey 未填写，去 **用户 → 认证方式 → BlogsClub → 设置** 填写 |
| BlogsClub 侧提示回调地址不匹配 | 登记的回调地址必须与 `halo.external-url` 完全一致，含协议与末尾无斜杠 |
| 日志出现 `ConfigMap/oauth2-blogsclub-config was not found` | 认证方式未启用或配置未保存，重新启用并保存一次 |
| 新账号邮箱为空 | 检查日志中 `[BlogsClub] 已缓存 BlogsClub 资料` 与 `[BlogsClub] 已自动写入 BlogsClub 邮箱` 两行是否出现 |
| BlogsClub 未开通 `email` 权限 | 授权页确认勾选了邮箱权限，否则新账号邮箱会为空 |

上述场景的日志均以 `[BlogsClub]` 开头，可直接在 Halo 日志中检索该前缀定位。

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
- AppKey（clientSecret）存放于 Halo 密钥（Secret）而非 ConfigMap，与 Halo 插件开发规范对密钥类凭据的存放要求一致

## 构建

```bash
./gradlew clean build
# 产物：build/libs/plugin-blogsclub-1.3.1.jar
```

运行单元测试：

```bash
./gradlew test
```

## 目录结构

```
src/main/java/io/github/ybrc114514/blogsclub/
  BlogsClubPlugin.java                        插件主类
  BlogsClubLoginConfiguration.java            插件内 Spring 装配（WebClient / 出站超时）
  BlogsClubAuthenticationWebFilter.java       OAuth2 回调过滤器（成功回调里串起建号 → 跳转）
  BlogsClubRedirectWebFilter.java             授权跳转过滤器（透传 popup display 参数）
  BlogsClubAuthorizationRequestResolver.java 授权请求参数解析
  BlogsClubOAuth2UserService.java             userinfo 适配器（解包 data / 映射 openid，token 仅走 header）
  BlogsClubClientRegistrationRepository.java  客户端注册仓库（AppID 读 ConfigMap，AppKey 存 Halo Secret）
  BlogsClubAccountProvisioningHandler.java    判定当前认证身份是否需要建号
  BlogsClubAccountProvisioner.java            创建 User + 写入 UserConnection（遵循站点注册设置）
  BlogsClubUserPreCreatingHandler.java        建号前按用户名关联，写入 BlogsClub 邮箱 / 昵称 / 头像
  BlogsClubClientRegistration.java            客户端注册 Scheme 定义
  BlogsClubAuthorizedClient.java              私有 GVK 授权客户端定义
  DefaultOAuth2AuthorizedClientService.java   授权客户端持久化
  UserConnectionDisconnectedListener.java     解绑时清理授权客户端
  BlogsClubRequests.java                      路径守卫（只处理插件自身回调路径）
  OAuth2Properties.java / BlogsClubConfigException.java / NotFoundException.java
src/main/resources/extensions/
  auth-provider.yaml                          认证方式定义
  client-registrations.yaml                   BlogsClub 端点与 scheme
  extension-definitions.yaml                  扩展点注册（含 user-pre-creating-handler）
  role.yaml                                   插件 RBAC 角色（最小权限）
  reverseproxy.yaml                           插件反向代理规则
  setting.yaml                                AppID / AppKey 配置表单
```

## 更新日志

### 1.3.1

- **修复**：资料回填（邮箱 / 昵称 / 头像）原先依赖线程本地变量传递会话 id，在 Halo 建号链路跨响应式线程后必然丢失，导致回填**静默失效**。现改为按建号用户名关联——用户名由插件生成并通过 Halo 交给 `UserPreCreatingHandler` 的 `User` 对象本身传递，任何线程调度下都不会丢失，且关联性比会话匹配更严格（只会写入这一次创建的账号）。
- **修复**：回调成功后插件曾用**裸 OAuth2 令牌**额外调用一次 `LoginHandlerEnhancer.onLoginSuccess`，而 Halo 内核的 OAuth2 映射过滤器随后还会用映射后的真实账号令牌再调用一次——前者会让 Halo 为不存在的用户名（openid）记录登录事件。现已移除该重复调用，登录增强统一由内核处理（与 Halo 官方 OAuth2 插件行为一致）。
- **安全**：AppKey（clientSecret）不再存放于 ConfigMap，改为存入 Halo 密钥（Secret）`blogsclub-oauth2-client-secret`；控制台表单中残留的旧值会在下次读取时自动迁移并清除。符合 Halo 插件开发技能中「密码 / 令牌 / API 密钥必须存 Secret」的要求。

### 1.3.0

- 按应用市场审核意见完成整改：资源类型改用插件私有分组（`blogsclub.oauth.ybrc114514.io`）与官方 OAuth2 插件隔离；补齐首次登录自动建号（遵循站点「允许注册」与默认角色设置）；资料写入严格关联当前认证身份；包名迁移到独立命名空间；`plugin.yaml` 的 app-id 修正为 `app-yjha8jz5`。

## 致谢

- [BlogsClub 开放平台](https://www.blogsclub.org/platform.html) —— 提供 OAuth 2.0 接口

## 许可证

[GPL-3.0](LICENSE)

## 反馈

问题反馈与功能建议请提交到 [GitHub Issues](https://github.com/YBRC114514/plugin-BlogsClub-OAuth/issues)。