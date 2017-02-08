/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.usergroups.ws;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.utils.internal.AlwaysIncreasingSystem2;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.server.usergroups.ws.GroupWsSupport.PARAM_GROUP_NAME;
import static org.sonar.server.usergroups.ws.GroupWsSupport.PARAM_LOGIN;
import static org.sonar.server.usergroups.ws.GroupWsSupport.PARAM_ORGANIZATION_KEY;

public class RemoveUserActionTest {

  @Rule
  public DbTester db = DbTester.create(new AlwaysIncreasingSystem2());
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private TestDefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private WsTester ws;

  @Before
  public void setUp() {
    GroupWsSupport groupSupport = new GroupWsSupport(db.getDbClient(), defaultOrganizationProvider);
    ws = new WsTester(new UserGroupsWs(new RemoveUserAction(db.getDbClient(), userSession, groupSupport)));
  }

  @Test
  public void does_nothing_if_user_is_not_in_group() throws Exception {
    // keep an administrator
    insertAnAdministratorInDefaultOrganization();

    GroupDto group = db.users().insertGroup(db.getDefaultOrganization(), "admins");
    UserDto user = db.users().insertUser("my-admin");
    loginAsAdminOnDefaultOrganization();

    newRequest()
      .setParam("id", group.getId().toString())
      .setParam("login", user.getLogin())
      .execute()
      .assertNoContent();

    assertThat(db.users().selectGroupIdsOfUser(user)).isEmpty();
  }

  @Test
  public void remove_user_by_group_id() throws Exception {
    // keep an administrator
    insertAnAdministratorInDefaultOrganization();
    GroupDto users = db.users().insertGroup(db.getDefaultOrganization(), "users");
    UserDto user = db.users().insertUser("my-admin");
    db.users().insertMember(users, user);
    loginAsAdminOnDefaultOrganization();

    newRequest()
      .setParam("id", users.getId().toString())
      .setParam("login", user.getLogin())
      .execute()
      .assertNoContent();

    assertThat(db.users().selectGroupIdsOfUser(user)).isEmpty();
  }

  @Test
  public void remove_user_by_group_name_in_default_organization() throws Exception {
    insertAnAdministratorInDefaultOrganization();
    GroupDto group = db.users().insertGroup(db.getDefaultOrganization(), "a_group");
    UserDto user = db.users().insertUser("a_user");
    db.users().insertMember(group, user);
    loginAsAdminOnDefaultOrganization();

    newRequest()
      .setParam(PARAM_GROUP_NAME, group.getName())
      .setParam(PARAM_LOGIN, user.getLogin())
      .execute()
      .assertNoContent();

    assertThat(db.users().selectGroupIdsOfUser(user)).isEmpty();
  }

  @Test
  public void remove_user_by_group_name_in_specific_organization() throws Exception {
    OrganizationDto org = db.organizations().insert();
    GroupDto group = db.users().insertGroup(org, "a_group");
    UserDto user = db.users().insertUser("a_user");
    db.users().insertMember(group, user);
    // keep an administrator
    db.users().insertAdminByUserPermission(org);

    loginAsAdmin(org);

    newRequest()
      .setParam(PARAM_ORGANIZATION_KEY, org.getKey())
      .setParam(PARAM_GROUP_NAME, group.getName())
      .setParam(PARAM_LOGIN, user.getLogin())
      .execute()
      .assertNoContent();

    assertThat(db.users().selectGroupIdsOfUser(user)).isEmpty();
  }

  @Test
  public void remove_user_only_from_one_group() throws Exception {
    // keep an administrator
    insertAnAdministratorInDefaultOrganization();

    OrganizationDto defaultOrg = db.getDefaultOrganization();
    GroupDto users = db.users().insertGroup(defaultOrg, "user");
    GroupDto admins = db.users().insertGroup(defaultOrg, "admins");
    UserDto user = db.users().insertUser("user");
    db.users().insertMember(users, user);
    db.users().insertMember(admins, user);
    loginAsAdminOnDefaultOrganization();

    newRequest()
      .setParam("id", admins.getId().toString())
      .setParam("login", user.getLogin())
      .execute()
      .assertNoContent();

    assertThat(db.users().selectGroupIdsOfUser(user)).containsOnly(users.getId());
  }

  @Test
  public void fail_if_unknown_group() throws Exception {
    UserDto user = db.users().insertUser("my-admin");

    expectedException.expect(NotFoundException.class);

    loginAsAdminOnDefaultOrganization();
    newRequest()
      .setParam("id", "42")
      .setParam("login", user.getLogin())
      .execute();
  }

  @Test
  public void fail_if_unknown_user() throws Exception {
    GroupDto group = db.users().insertGroup(db.getDefaultOrganization(), "admins");

    expectedException.expect(NotFoundException.class);

    loginAsAdminOnDefaultOrganization();
    newRequest()
      .setParam("id", group.getId().toString())
      .setParam("login", "my-admin")
      .execute();
  }

  @Test
  public void throw_ForbiddenException_if_not_administrator_of_organization() throws Exception {
    OrganizationDto org = db.organizations().insert();
    GroupDto group = db.users().insertGroup(org, "a-group");
    UserDto user = db.users().insertUser();
    db.users().insertMember(group, user);
    loginAsAdminOnDefaultOrganization();

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    newRequest()
      .setParam("id", group.getId().toString())
      .setParam("login", user.getLogin())
      .execute();
  }

  @Test
  public void fail_to_remove_the_last_administrator() throws Exception {
    OrganizationDto org = db.organizations().insert();
    GroupDto adminGroup = db.users().insertGroup(org, "sonar-admins");
    db.users().insertPermissionOnGroup(adminGroup, GlobalPermissions.SYSTEM_ADMIN);
    UserDto adminUser = db.users().insertUser("the-single-admin");
    db.users().insertMember(adminGroup, adminUser);
    loginAsAdmin(org);

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The last administrator user cannot be removed");

    newRequest()
      .setParam("id", adminGroup.getId().toString())
      .setParam("login", adminUser.getLogin())
      .execute();
  }

  private WsTester.TestRequest newRequest() {
    return ws.newPostRequest("api/user_groups", "remove_user");
  }

  private void loginAsAdminOnDefaultOrganization() {
    loginAsAdmin(db.getDefaultOrganization());
  }

  private void loginAsAdmin(OrganizationDto org) {
    userSession.logIn("admin").addOrganizationPermission(org.getUuid(), GlobalPermissions.SYSTEM_ADMIN);
  }

  private UserDto insertAnAdministratorInDefaultOrganization() {
    return db.users().insertAdminByUserPermission(db.getDefaultOrganization());
  }
}
