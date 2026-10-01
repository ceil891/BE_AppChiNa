package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DynamicPermissionTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    final List<String> roles=new ArrayList<>();
    @BeforeEach void setup(){api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;}
    @AfterEach void cleanup(){api.cleanup();for(String code:roles)jdbc.sql("DELETE FROM roles WHERE code=:code").param("code",code).update();}
    String role(AdminUserApiTests.Client root,List<String> permissions)throws Exception{
        String code="TEST_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);roles.add(code);
        var response=root.write("POST","/admin/roles",Map.of("code",code,"name","Test dynamic role","description","Temporary role","permissions",permissions));
        api.status(response,201);return code;
    }
    Map<String,Object> changes(List<String> permissions){return Map.of("name","Updated role","description","Updated description","permissions",permissions);}
    @Test void dynamicReaderCanOnlyReadTheGrantedModule()throws Exception{
        var root=api.admin();String code=role(root,List.of("vocabulary.read"));
        var account=api.create(root,code);var reader=api.login(account,AdminUserApiTests.PASSWORD);
        var profile=api.json(reader.get("/auth/me"));
        assertThat(profile.get("role").asText()).isEqualTo(code);
        assertThat(profile.get("permissions").size()).isEqualTo(1);
        assertThat(profile.get("permissions").get(0).asText()).isEqualTo("vocabulary.read");
        api.status(reader.get("/admin/vocabulary"),200);
        api.status(reader.get("/admin/vocabulary/1"),200);
        for(String path:List.of("/admin/lessons","/admin/users","/admin/roles","/admin/roles/options","/admin/roles/permissions"))api.status(reader.get(path),403);
        api.status(reader.write("POST","/admin/vocabulary",Map.of()),403);
        api.status(reader.write("PUT","/admin/vocabulary/1",Map.of()),403);
        api.status(reader.write("DELETE","/admin/vocabulary/1",null),403);
        api.status(reader.write("POST","/admin/roles",Map.of()),403);
        api.status(reader.write("PATCH","/profile",Map.of("displayName","Test","permissions",List.of("users.read"))),400);
    }
    @Test void changingRolePermissionsRevokesAllAssignedSessionsAndTakesEffectAtNextLogin()throws Exception{
        var root=api.admin();String code=role(root,List.of("vocabulary.read"));
        var a=api.create(root,code);var b=api.create(root,code);
        var clientA=api.login(a,AdminUserApiTests.PASSWORD);var dormantB=api.login(b,AdminUserApiTests.PASSWORD);
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("lessons.read"))),200);
        api.status(clientA.get("/auth/me"),401);
        // Even restoring the old permission set must not revive an unused old cookie.
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("vocabulary.read"))),200);
        api.status(dormantB.get("/auth/me"),401);
        clientA=api.login(a,AdminUserApiTests.PASSWORD);
        api.status(clientA.get("/admin/vocabulary"),200);api.status(clientA.get("/admin/lessons"),403);
        api.status(root.write("PUT","/admin/users/"+a.get("id").asText(),api.update("Learner","USER",true)),200);
        api.status(clientA.get("/admin/vocabulary"),401);
        api.status(api.login(a,AdminUserApiTests.PASSWORD).get("/admin/vocabulary"),403);
    }
    @Test void userNameEditorCannotAssignRolesLockOrResetPasswords()throws Exception{
        var root=api.admin();String code=role(root,List.of("users.read","users.update"));
        var editor=api.login(api.create(root,code),AdminUserApiTests.PASSWORD);
        var target=api.create(root,"USER");String path="/admin/users/"+target.get("id").asText();
        api.status(editor.get("/admin/users"),200);api.status(editor.get("/admin/roles/options"),200);
        api.status(editor.write("PUT",path,api.update("Changed by editor","USER",true)),200);
        api.status(editor.write("PUT",path,api.update("Changed by editor",code,true)),403);
        api.status(editor.write("PUT",path,api.update("Changed by editor","USER",false)),403);
        api.status(editor.write("PATCH",path+"/status",Map.of("enabled",false)),403);
        api.status(editor.write("POST",path+"/password",Map.of("password",AdminUserApiTests.NEW_PASSWORD)),403);
        api.status(editor.write("DELETE",path,null),403);
        api.status(editor.write("POST","/admin/users",Map.of()),403);
        assertThat(api.json(root.get(path)).get("role").asText()).isEqualTo("USER");
        assertThat(api.json(root.get(path)).get("enabled").asBoolean()).isTrue();
    }
    @Test void roleDelegationCannotGrantUnownedPermissionsOrTakeOverPowerfulAccounts()throws Exception{
        var root=api.admin();JsonNode rootAccount=api.json(root.get("/auth/me"));
        var own=List.of("roles.read","roles.create","roles.update","roles.delete","users.read","users.create","users.assign_role","users.password","users.status","users.update","users.delete");
        String code=role(root,own);
        var manager=api.login(api.create(root,code),AdminUserApiTests.PASSWORD);
        String weak=role(root,List.of()); String strong=role(root,List.of("vocabulary.read"));
        api.status(manager.write("PUT","/admin/roles/"+weak,changes(List.of("vocabulary.read"))),403);
        api.status(manager.write("PUT","/admin/roles/"+code,changes(List.of("vocabulary.read"))),403);
        api.status(manager.write("PUT","/admin/roles/"+strong,changes(List.of())),403);
        api.status(manager.write("DELETE","/admin/roles/"+strong,null),403);
        var target=api.create(root,"USER");String targetPath="/admin/users/"+target.get("id").asText();
        api.status(manager.write("PUT",targetPath,api.update("Escalate","ADMIN",true)),403);
        api.status(manager.write("PUT",targetPath,api.update("Escalate",strong,true)),403);
        api.status(manager.write("PUT",targetPath,api.update("Assigned",weak,true)),200);
        String rootPath="/admin/users/"+rootAccount.get("id").asText();
        api.status(manager.write("POST",rootPath+"/password",Map.of("password",AdminUserApiTests.NEW_PASSWORD)),403);
        api.status(manager.write("PATCH",rootPath+"/status",Map.of("enabled",false)),403);
        api.status(manager.write("DELETE",rootPath,null),403);
        String unused=api.email();
        api.status(manager.write("POST","/admin/users",Map.of("email",unused,"password",AdminUserApiTests.PASSWORD,"displayName","Escalation","role","ADMIN")),403);
        api.status(root.get("/admin/users"),200);
    }
    @Test void rolesCrudValidatesDependenciesAndProtectsSystemAndAssignedRoles()throws Exception{
        var root=api.admin();String code=role(root,List.of("lessons.read","vocabulary.read","lessons.update"));
        api.status(root.get("/admin/roles/permissions"),200);
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("lessons.update"))),400);
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("lessons.read","lessons.update"))),400);
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("fake.permission"))),400);
        api.status(root.write("PUT","/admin/roles/"+code,changes(List.of("users.read","users.read"))),400);
        assertThat(api.json(root.get("/admin/roles/"+code)).get("permissions").size()).isEqualTo(3);
        api.status(root.write("POST","/admin/roles",Map.of("code",code,"name","Duplicate","description","","permissions",List.of())),409);
        for(String system:List.of("USER","ADMIN")){
            api.status(root.write("DELETE","/admin/roles/"+system,null),409);
            api.status(root.write("PUT","/admin/roles/"+system,changes(List.of())),409);
        }
        var target=api.create(root,code);
        api.status(root.write("DELETE","/admin/roles/"+code,null),409);
        api.status(root.write("PUT","/admin/users/"+target.get("id").asText(),api.update("Moved","USER",true)),200);
        api.status(root.write("DELETE","/admin/roles/"+code,null),204);
        api.status(root.get("/admin/roles/"+code),404);
        api.status(root.write("PUT","/admin/users/"+target.get("id").asText(),api.update("Missing",code,true)),400);
    }
    @Test void contentEditorCanCreateAndUpdateButCannotDelete()throws Exception{
        var root=api.admin();String code=role(root,List.of("vocabulary.read","lessons.read","lessons.create","lessons.update"));
        var editor=api.login(api.create(root,code),AdminUserApiTests.PASSWORD);
        long id=0;
        try{
            var created=editor.write("POST","/admin/lessons",Map.of("title","Dynamic editor test","description","Temporary","position",99888,"wordIds",List.of(1,2)));
            api.status(created,201);id=api.json(created).get("lesson").get("id").asLong();
            api.status(editor.write("PUT","/admin/lessons/"+id,Map.of("title","Edited","description","Changed","position",99888,"wordIds",List.of(2,1))),200);
            assertThat(api.json(editor.get("/admin/lessons/"+id)).get("words").get(0).get("id").asLong()).isEqualTo(2);
            api.status(editor.write("DELETE","/admin/lessons/"+id,null),403);
            api.status(editor.write("POST","/admin/vocabulary",Map.of()),403);
        }finally{if(id>0)api.status(root.write("DELETE","/admin/lessons/"+id,null),204);}
    }
}
