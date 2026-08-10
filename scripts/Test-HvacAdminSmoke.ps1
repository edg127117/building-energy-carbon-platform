[CmdletBinding()]
param(
    [string]$ApiBase = 'http://127.0.0.1:8081/api',
    [string]$AdminUsername = 'admin',
    [string]$InitialBuildingId = 'BLD001',
    [string]$RequestedBuildingId = 'BLD-SMOKE-002'
)

$ErrorActionPreference = 'Stop'
$adminPassword = $env:HVAC_SMOKE_ADMIN_PASSWORD
$adminToken = $null
$temporaryToken = $null
$temporaryUserId = $null
$temporaryMenuId = $null
$roleId = $null
$originalRoleMenuIds = $null
$roleMenusChanged = $false
$temporaryUsername = 'hvac_admin_smoke_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$temporaryPassword = [Guid]::NewGuid().ToString('N') + 'Aa1!'
$resetPassword = [Guid]::NewGuid().ToString('N') + 'Bb2!'

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        [string]$Token,
        [object]$Body
    )

    $parameters = @{
        Method = $Method
        Uri = "$($ApiBase.TrimEnd('/'))/$($Path.TrimStart('/'))"
        Headers = @{}
        TimeoutSec = 20
    }
    if ($Token) {
        $parameters.Headers.Authorization = "Bearer $Token"
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Get-RequiredToken([string]$Username, [string]$Password) {
    $response = Invoke-JsonApi -Method Post -Path '/auth/login' -Body @{
        username = $Username
        password = $Password
    }
    if (-not $response.success -or -not $response.data.token) {
        throw '登录未返回有效 JWT'
    }
    return $response.data.token
}

function Assert-Success([object]$Response, [string]$Operation) {
    if ($null -eq $Response -or -not $Response.success) {
        throw "$Operation 未返回成功结果"
    }
}

function Get-FlattenedMenus([object[]]$Menus) {
    $result = [Collections.Generic.List[object]]::new()
    foreach ($menu in @($Menus)) {
        $result.Add($menu)
        if ($menu.children) {
            foreach ($child in @(Get-FlattenedMenus @($menu.children))) {
                $result.Add($child)
            }
        }
    }
    return @($result)
}

function Test-TokenRejected([string]$Token) {
    try {
        Invoke-JsonApi -Method Get -Path '/auth/me' -Token $Token | Out-Null
        return $false
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        return $status -eq 401 -or $status -eq 403
    }
}

if (-not $adminPassword) {
    throw 'HVAC_SMOKE_ADMIN_PASSWORD 必须通过进程环境提供'
}

try {
    $adminToken = Get-RequiredToken $AdminUsername $adminPassword

    $adminTree = Invoke-JsonApi -Method Get -Path '/menu/admin/tree' -Token $adminToken
    Assert-Success $adminTree '查询完整菜单树'
    $allMenus = @(Get-FlattenedMenus @($adminTree.data))
    if (-not ($allMenus | Where-Object { $_.visible -eq 0 })) {
        throw '管理员完整菜单树未返回隐藏菜单'
    }
    if (-not ($allMenus | Where-Object { $_.status -eq 0 })) {
        throw '管理员完整菜单树未返回停用菜单'
    }

    $createdUser = Invoke-JsonApi -Method Post -Path '/system/users' -Token $adminToken -Body @{
        username = $temporaryUsername
        password = $temporaryPassword
        nickname = '后台管理真实冒烟账号'
        roleKeys = @('BUILDING_OWNER')
        buildingIds = @($InitialBuildingId)
    }
    Assert-Success $createdUser '创建临时用户'
    $temporaryUserId = $createdUser.data.id

    $detail = Invoke-JsonApi -Method Get -Path "/system/users/$temporaryUserId" -Token $adminToken
    if (-not $detail.success -or $detail.data.roles -notcontains 'BUILDING_OWNER' -or
        $detail.data.buildingIds -notcontains $InitialBuildingId) {
        throw '临时用户详情、角色或建筑授权不一致'
    }

    $temporaryToken = Get-RequiredToken $temporaryUsername $temporaryPassword
    $currentMenus = Invoke-JsonApi -Method Get -Path '/menu/current' -Token $temporaryToken
    Assert-Success $currentMenus '查询临时用户当前菜单'
    $currentPaths = @(Get-FlattenedMenus @($currentMenus.data) | ForEach-Object { $_.path })
    if ($currentPaths -notcontains '/hvac-demo' -or
        @($currentPaths | Where-Object { $_ -like '/system/*' }).Count -gt 0) {
        throw '非管理员菜单没有遵守 HVAC 可见、后台管理不可见边界'
    }

    Invoke-JsonApi -Method Put -Path "/system/users/$temporaryUserId/status" -Token $adminToken -Body @{ status = 0 } | Out-Null
    if (-not (Test-TokenRejected $temporaryToken)) {
        throw '旧会话仍然有效，禁用用户后会话未失效'
    }
    Invoke-JsonApi -Method Put -Path "/system/users/$temporaryUserId/status" -Token $adminToken -Body @{ status = 1 } | Out-Null
    Invoke-JsonApi -Method Put -Path "/system/users/$temporaryUserId/password" -Token $adminToken -Body @{ password = $resetPassword } | Out-Null
    $temporaryToken = Get-RequiredToken $temporaryUsername $resetPassword

    $createdMenu = Invoke-JsonApi -Method Post -Path '/menu/add' -Token $adminToken -Body @{
        parentId = 200
        menuName = '后台管理冒烟隐藏项'
        menuType = 'C'
        path = '/system/smoke-hidden'
        component = 'admin/smoke-hidden'
        perms = 'system:smoke:hidden'
        icon = 'shield-check'
        visible = 0
        status = 0
        sortOrder = 999
    }
    Assert-Success $createdMenu '创建隐藏菜单'
    $temporaryMenuId = $createdMenu.data.id

    $updatedMenu = Invoke-JsonApi -Method Put -Path '/menu/update' -Token $adminToken -Body @{
        id = $temporaryMenuId
        parentId = 200
        menuName = '后台管理冒烟隐藏项（已更新）'
        menuType = 'C'
        path = '/system/smoke-hidden'
        component = 'admin/smoke-hidden'
        perms = 'system:smoke:hidden'
        icon = 'shield-check'
        visible = 0
        status = 0
        sortOrder = 998
    }
    Assert-Success $updatedMenu '更新隐藏菜单'

    $available = Invoke-JsonApi -Method Get -Path '/building-access/available' -Token $temporaryToken
    Assert-Success $available '查询可申请建筑'
    if (@($available.data | Where-Object { $_.buildingId -eq $RequestedBuildingId }).Count -ne 1) {
        throw '待申请建筑不在可申请范围'
    }
    $request = Invoke-JsonApi -Method Post -Path '/building-access/requests' -Token $temporaryToken -Body @{
        buildingId = $RequestedBuildingId
        reason = '后台管理真实冒烟验证'
    }
    Assert-Success $request '提交建筑授权申请'
    $requestId = $request.data.id
    Invoke-JsonApi -Method Put -Path "/system/building-access/requests/$requestId/approve" -Token $adminToken -Body @{
        comment = '后台管理真实冒烟批准'
    } | Out-Null
    $approvedDetail = Invoke-JsonApi -Method Get -Path "/system/users/$temporaryUserId" -Token $adminToken
    if ($approvedDetail.data.buildingIds -notcontains $RequestedBuildingId) {
        throw '建筑授权审批后未写入用户建筑范围'
    }

    $roles = Invoke-JsonApi -Method Get -Path '/system/roles' -Token $adminToken
    $buildingOwnerRole = @($roles.data | Where-Object { $_.roleKey -eq 'BUILDING_OWNER' }) | Select-Object -First 1
    if ($null -eq $buildingOwnerRole) {
        throw '固定角色 BUILDING_OWNER 不存在'
    }
    $roleId = $buildingOwnerRole.id
    $roleMenus = Invoke-JsonApi -Method Get -Path "/system/roles/$roleId/menus" -Token $adminToken
    $originalRoleMenuIds = @($roleMenus.data)
    Invoke-JsonApi -Method Put -Path "/system/roles/$roleId/menus" -Token $adminToken -Body @{ menuIds = @(100) } | Out-Null
    $roleMenusChanged = $true

    Invoke-JsonApi -Method Put -Path "/system/users/$temporaryUserId/password" -Token $adminToken -Body @{ password = $temporaryPassword } | Out-Null
    $temporaryToken = Get-RequiredToken $temporaryUsername $temporaryPassword
    $reducedMenus = Invoke-JsonApi -Method Get -Path '/menu/current' -Token $temporaryToken
    $reducedPaths = @(Get-FlattenedMenus @($reducedMenus.data) | ForEach-Object { $_.path })
    if ($reducedPaths -contains '/hvac-demo') {
        throw '固定角色菜单替换后当前菜单仍包含已撤销叶子'
    }

    Write-Output 'HVAC_ADMIN_SMOKE_OK'
} catch {
    Write-Error 'HVAC 后台管理真实冒烟失败；敏感请求数据已隐藏'
    throw
} finally {
    if ($roleMenusChanged -and $roleId -and $null -ne $originalRoleMenuIds -and $adminToken) {
        try {
            # restore role menus
            Invoke-JsonApi -Method Put -Path "/system/roles/$roleId/menus" -Token $adminToken -Body @{
                menuIds = @($originalRoleMenuIds)
            } | Out-Null
        } catch {
            Write-Warning '恢复固定角色菜单失败'
        }
    }
    if ($temporaryMenuId -and $adminToken) {
        try {
            Invoke-JsonApi -Method Delete -Path "/menu/delete/$temporaryMenuId" -Token $adminToken | Out-Null
        } catch {
            Write-Warning 'DELETE temp menu failed'
        }
    }
    if ($temporaryUserId -and $adminToken) {
        try {
            # DELETE temp user
            Invoke-JsonApi -Method Delete -Path "/system/users/$temporaryUserId" -Token $adminToken | Out-Null
        } catch {
            Write-Warning 'DELETE temp user failed'
        }
    }
    $adminToken = $null
    $temporaryToken = $null
    $temporaryPassword = $null
    $resetPassword = $null
}
