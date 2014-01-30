/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.pull.shared;

import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.CommitStatus;
import org.eclipse.egit.github.core.IRepositoryIdProvider;
import org.eclipse.egit.github.core.PullRequest;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.PullRequestService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.jboss.pull.shared.evaluators.ServicePullEvaluator;
import org.jboss.pull.shared.spi.PullEvaluator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shared functionality regarding mergeable PRs, Github and Bugzilla.
 *
 * @author <a href="mailto:istudens@redhat.com">Ivo Studensky</a>
 */
public class PullHelper {

//    alternative way configure in property file, better configuration but hard to debug
//    private static Pattern BUGZILLA_ID_PATTERN;
//    private static Pattern UPSTREAM_PATTERN;
//    private static Pattern BUILD_OUTCOME_PATTERN;
    private static final Pattern BUGZILLA_ID_PATTERN = Pattern.compile("bugzilla\\.redhat\\.com/show_bug\\.cgi\\?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPSTREAM_PATTERN = Pattern.compile("github\\.com/(wildfly/wildfly|jbossas/jboss-eap)/pull/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD_OUTCOME = Pattern.compile("outcome was (\\*\\*)?+(SUCCESS|FAILURE|ABORTED)(\\*\\*)?+ using a merge of ([a-z0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final String BUGZILLA_BASE = "https://bugzilla.redhat.com/";

    private final String GITHUB_ORGANIZATION;
    private final String GITHUB_ORGANIZATION_UPSTREAM;
    private final String GITHUB_REPO;
    private final String GITHUB_REPO_UPSTREAM;
    private final String GITHUB_LOGIN;
    private final String GITHUB_TOKEN;

    private final String BUGZILLA_LOGIN;
    private final String BUGZILLA_PASSWORD;

    private final IRepositoryIdProvider repository;
    private final IRepositoryIdProvider repositoryUpstream;
    private final RepositoryService repositoryService;
    private final CommitService commitService;
    private final IssueService issueService;
    private final PullRequestService pullRequestService;

    private final Bugzilla bugzillaClient;

    private final Properties props;

    private final ServicePullEvaluator evaluator = new ServicePullEvaluator();

    public PullHelper(final String configurationFileProperty, final String configurationFileDefault) throws Exception {
        try {
            props = Util.loadProperties(configurationFileProperty, configurationFileDefault);

//            alternative way configure in property file, better configuration but hard to debug
//            String buildIdPattern = Util.require(props, "bugzilla.id.pattern");
//            BUGZILLA_ID_PATTERN = Pattern.compile(buildIdPattern, Pattern.CASE_INSENSITIVE);
//            String upstreamPattern = Util.require(props, "upstream.pattern");
//            UPSTREAM_PATTERN = Pattern.compile(upstreamPattern, Pattern.CASE_INSENSITIVE);
//            String buildOutcomePattern = Util.require(props, "build.outcome.pattern");
//            BUILD_OUTCOME_PATTERN = Pattern.compile(buildOutcomePattern, Pattern.CASE_INSENSITIVE);

            GITHUB_ORGANIZATION = Util.require(props, "github.organization");
            GITHUB_ORGANIZATION_UPSTREAM = Util.require(props, "github.organization.upstream");
            GITHUB_REPO = Util.require(props, "github.repo");
            GITHUB_REPO_UPSTREAM = Util.require(props, "github.repo.upstream");
            GITHUB_LOGIN = Util.require(props, "github.login");
            GITHUB_TOKEN = Util.get(props, "github.token");

            // initialize client and services
            GitHubClient client = new GitHubClient();
            if (GITHUB_TOKEN != null && GITHUB_TOKEN.length() > 0)
                client.setOAuth2Token(GITHUB_TOKEN);
            repository = RepositoryId.create(GITHUB_ORGANIZATION, GITHUB_REPO);
            repositoryUpstream = RepositoryId.create(GITHUB_ORGANIZATION_UPSTREAM, GITHUB_REPO_UPSTREAM);
            repositoryService = new RepositoryService(client);
            commitService = new CommitService(client);
            issueService = new IssueService(client);
            pullRequestService = new PullRequestService(client);

            BUGZILLA_LOGIN = Util.require(props, "bugzilla.login");
            BUGZILLA_PASSWORD = Util.require(props, "bugzilla.password");

            // initialize bugzilla client
            bugzillaClient = new Bugzilla(BUGZILLA_BASE, BUGZILLA_LOGIN, BUGZILLA_PASSWORD);

            // initialize the service evaluator
            evaluator.init(this, props);

        } catch (Exception e) {
            System.err.println("Cannot initialize: " + e);
            e.printStackTrace(System.err);
            throw e;
        }
    }

    public PullEvaluator.Result isMergeable(final PullRequest pull) {
        return evaluator.isMergeable(pull);
    }

    public boolean isMerged(final PullRequest pull) {
        if (pull == null) {
            return false;
        }

        if (! pull.getState().equals("closed")) {
            return false;
        }

        try {
            if (pullRequestService.isMerged(repositoryUpstream, pull.getNumber())) {
                return true;
            }
        } catch (IOException ignore) {
            System.err.printf("Cannot get Merged information of the pull request %d: %s.\n", pull.getNumber(), ignore);
            ignore.printStackTrace(System.err);
        }

        try {
            final List<Comment> comments = issueService.getComments(repositoryUpstream, pull.getNumber());
            for (Comment comment : comments) {
                if (comment.getBody().toLowerCase().indexOf("merged") != -1) {
                    return true;
                }
			}
        } catch (IOException ignore) {
            System.err.printf("Cannot get comments of the pull request %d: %s.\n", pull.getNumber(), ignore);
            ignore.printStackTrace(System.err);
        }

        return false;
    }

    public List<Integer> checkBugzillaId(String body) {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        Matcher matcher = BUGZILLA_ID_PATTERN.matcher(body);
        while (matcher.find()) {
            try {
                ids.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignore) {
                System.err.println("Invalid bug number: " + ignore);
            }
        }
        return ids;
    }

    public Map<Integer, String> checkUpStreamPullRequestId(String body) {
        Map<Integer, String> ids = new HashMap<Integer, String>();
        Matcher matcher = UPSTREAM_PATTERN.matcher(body);
        while (matcher.find()) {
            try {
                String organizationBranch = matcher.group(1);
                Integer id = Integer.parseInt(matcher.group(2));
                ids.put(id, organizationBranch);
            } catch (NumberFormatException ignore) {
                System.err.println("Invalid pull request number: " + ignore);
            }
        }
        return ids;
    }

    public List<Bug> getBug(PullRequest pull) {
        List<Integer> ids = checkBugzillaId(pull.getBody());
        ArrayList<Bug> bugs = new ArrayList<Bug>();

        for (Integer id : ids) {
            try {
                Bug bug = bugzillaClient.getBug(id);
                if(bug != null)
                    bugs.add(bug);
            } catch (Exception ignore) {
                System.err.printf("Cannot get a bug related to the pull request %d: %s.\n", pull.getNumber(), ignore);
            }
        }
        return bugs;
    }

    public BuildResult checkBuildResult(PullRequest pullRequest) {
        BuildResult buildResult = BuildResult.UNKNOWN;
        List<Comment> comments;
        try {
            comments = issueService.getComments(repository, pullRequest.getNumber());
        } catch (IOException e) {
            System.err.println("Error to get comments for pull request : " + pullRequest.getNumber());
            e.printStackTrace(System.err);
            return buildResult;
        }
        for (Comment comment : comments) {
            Matcher matcher = BUILD_OUTCOME.matcher(comment.getBody());
            while (matcher.find()) {
                buildResult = BuildResult.valueOf(matcher.group(2));
            }
        }
        return buildResult;
    }

    public List<PullRequest> getUpstreamPullRequest(PullRequest pull) throws IOException {
        ArrayList<PullRequest> upstreamPulls = new ArrayList<PullRequest>();

        Map<Integer, String> pullIds = checkUpStreamPullRequestId(pull.getBody());

        for (Integer key : pullIds.keySet()) {
            String organizationBranch = pullIds.get(key);
            String[] organizationRepo = organizationBranch.split("/");
            if (organizationRepo.length != 2)
                throw new RuntimeException("organization/repository format error: " + organizationBranch);

            upstreamPulls.add(pullRequestService.getPullRequest(
                    RepositoryId.create(organizationRepo[0], organizationRepo[1]), key));
        }
        return upstreamPulls;
    }

    public void updateBugzillaStatus(PullRequest pull, Bug.Status status) throws Exception {
        List<Bug> bugs = getBug(pull);
        for (Bug bug : bugs) {
            bugzillaClient.updateBugzillaStatus(bug.getId(), status);
        }
    }

    public void postGithubStatus(PullRequest pull, String targetUrl, String status) {
        try {
            CommitStatus commitStatus = new CommitStatus();
            commitStatus.setTargetUrl(targetUrl);
            commitStatus.setState(status);
            commitService.createStatus(repository, pull.getHead().getSha(), commitStatus);
        } catch (Exception e) {
            System.err.printf("Problem posting a status build for sha: %s\n", pull.getHead().getSha());
            e.printStackTrace(System.err);
        }
    }

    public void postGithubComment(PullRequest pull, String comment) {
        try {
            issueService.createComment(repository, pull.getNumber(), comment);
        } catch (IOException e) {
            System.err.printf("Problem posting a comment build for pull: %d\n", pull.getNumber());
            e.printStackTrace(System.err);
        }
    }


    public Properties getProps() {
        return props;
    }

    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    public IRepositoryIdProvider getRepository() {
        return repository;
    }

    public IRepositoryIdProvider getRepositoryUpstream() {
        return repositoryUpstream;
    }

    public CommitService getCommitService() {
        return commitService;
    }

    public IssueService getIssueService() {
        return issueService;
    }

    public PullRequestService getPullRequestService() {
        return pullRequestService;
    }

    public String getGithubLogin() {
        return GITHUB_LOGIN;
    }

    public Set<String> getCoveredBranches() {
        return evaluator.getCoveredBranches();
    }
}
