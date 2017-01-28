import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.ModifiedValue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder
import com.atlassian.jira.issue.watchers.WatcherManager
import com.atlassian.jira.user.ApplicationUser
import com.google.common.base.Splitter
import com.google.common.collect.Lists
import groovy.sql.Sql
import org.apache.commons.lang3.StringUtils
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.ofbiz.core.entity.ConnectionFactory
import org.ofbiz.core.entity.DelegatorInterface

import java.sql.Connection

import static java.util.Collections.EMPTY_LIST

/**
 * Created by rbhunia on 12/5/2016.
 */
// Initialize logger and set the level to DEBUG.
logger = log as Logger
logger.setLevel(Level.DEBUG)

// Initialize issue.
issue = issue as Issue

customFields = getCustomFields()

issueOnCreate(issue)

def issueOnCreate(issue) {
    logger.info("************************************************************************************")
    logger.info("Start executing auto assignment logic for issue ${issue.getKey()} from post function")

    def autoAssigneeConfig = loadAutoAssigneeConfig(issue) as Map
    logger.info("Configuration data ${autoAssigneeConfig} retrieved from the database.")

    if (!issue.getAssignee()?.getName()) {
        setAssignee(issue, autoAssigneeConfig)
    }

    setValidator(issue, autoAssigneeConfig)

    setWatchers(issue, autoAssigneeConfig)

    logger.info("Completed executing auto assignment logic for issue ${issue.getKey()} from post function")
    logger.info("****************************************************************************************")
}

def loadAutoAssigneeConfig(issue) {
    def autoAssigneeConfig = [:]
    def queryParams = getQueryParams(issue)
    logger.info("Parameters passed to the sql query ${queryParams}")
    def connection = getConnection() as Connection
    def sql = new Sql(connection)
    try {
        sql.call("{call spGetAutoAssignment(?,?,?,?,?,?,?,?)}", queryParams.toList()) {
            assignee, watcher, validator ->
                autoAssigneeConfig.put("Assignee", assignee)
                autoAssigneeConfig.put("Watcher", watcher)
                autoAssigneeConfig.put("Validator", validator)

        }
    } finally {
        sql.close()
    }
    return autoAssigneeConfig
}

def setAssignee(Issue issue, autoAssigneeConfig) {
    def configuredAssignee = autoAssigneeConfig.get("Assignee")
    if (configuredAssignee) {
        def issueToUpdate = (MutableIssue) issue
        if (isActiveUser(configuredAssignee)) {
            issueToUpdate.setAssigneeId(configuredAssignee)
        }
    }
    updateIssue(issue)
}

def setValidator(issue, autoAssigneeConfig) {
    def VALIDATOR = "Validator"
    def validator = issue.getCustomFieldValue(customFields.get(VALIDATOR))
    if (validator)
        return
    else {
        def configuredValidator = autoAssigneeConfig.get(VALIDATOR) as String
        StringUtils.isEmpty(configuredValidator) ? setNullValue(VALIDATOR) :
                isActiveUser(configuredValidator) ? setValue(VALIDATOR, getUser(configuredValidator)) : setNullValue(VALIDATOR)
    }
}

def setWatchers(Issue issue, autoAssigneeConfig) {
    def finalWatchers = [] as HashSet<ApplicationUser>
    def configuredWatchers = getConfiguredWatchers(autoAssigneeConfig)
    def userEnteredWatchers = issue.getCustomFieldValue(customFields.get("Watchers"))
    logger.info("User has added ${userEnteredWatchers ? userEnteredWatchers : " no user "} as Watchers upon issue creation.")

    if (userEnteredWatchers) {
        finalWatchers.addAll(userEnteredWatchers)
    }
    finalWatchers.add(getUser(issue.getAssignee()?.getName()))
    finalWatchers.add(getUser(issue.getReporter()?.getName()))

    configuredWatchers?.each { user ->
        if (isActiveUser(user)) {
            finalWatchers.add(getUser(user))
        }
    }
    finalWatchers.removeAll([null])
    logger.info("Final list of users added as watcher ${finalWatchers}")
    if (finalWatchers) {
        def watcherManager = ComponentAccessor.getWatcherManager() as WatcherManager
        finalWatchers.each {
            watcherManager.startWatching(it, issue)
        }
    }
}

def getConfiguredWatchers(autoAssigneeConfig) {
    return StringUtils.isNotEmpty(autoAssigneeConfig.get("Watcher")) ?
            fromCommaSeparatedString(autoAssigneeConfig.get("Watcher")) :
            EMPTY_LIST
}

def getQueryParams(Issue issue) {
    def queryParams = [] as List
    queryParams << issue.getProjectObject().getKey()
    queryParams << issue.getIssueTypeId()
    if (issue.isSubTask()) {
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Ticket Program"))?.toString())
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Ticket Triage Category"))?.toString())
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Ticket Triage Assignment"))?.toString())
    } else {
        logger.info("Inside task block.")
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Program"))?.toString())
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Triage Category"))?.toString())
        queryParams << removeSquareBracket(issue.getCustomFieldValue(customFields.get("Triage Assignment"))?.toString())
    }
    queryParams << Sql.VARCHAR
    queryParams << Sql.VARCHAR
    queryParams << Sql.VARCHAR
    return queryParams.flatten()
}

/** Get ApplicationUser object from user id.*/
def getUser(user) {
    def userManager = ComponentAccessor.getUserManager()
    return userManager.getUserByName(user)
}

/**
 * Updates a custom field's value to null.
 * */
def setNullValue(customFieldName) {
    setValue(customFieldName, null)
}

/**
 * Update a custom field with a provided value.
 * */
def setValue(customFieldName, customFieldValue) {
    def issueToUpdate = (MutableIssue) issue as MutableIssue
    def customField = customFields.get(customFieldName)
    def fieldLayoutManager = ComponentAccessor.getFieldLayoutManager()
    def fieldLayoutItem = fieldLayoutManager.getFieldLayout(issue)
            .getFieldLayoutItem(customField)

    issueToUpdate.setCustomFieldValue(customField, customFieldValue)
    customField.updateValue(fieldLayoutItem, issue,
            new ModifiedValue(null, customFieldValue), new DefaultIssueChangeHolder())
    //updateIssue(issue)
}

def isActiveUser(userKey) {
    def userManager = ComponentAccessor.getUserManager()
    def user = userManager.getUserByKey(userKey) as ApplicationUser
    return user?.isActive()
}

def getCustomFields() {
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def customFieldsMap = [:]

    customFieldsMap.put("Validator", customFieldManager.getCustomFieldObjectsByName("Validator").first())
    customFieldsMap.put("Watchers", customFieldManager.getCustomFieldObjectsByName("Watchers").first())
    customFieldsMap.put("Ticket Program", customFieldManager.getCustomFieldObjectsByName("Ticket Program").first())
    customFieldsMap.put("Ticket Triage Assignment", customFieldManager.getCustomFieldObjectsByName("Ticket Triage Assignment").first())
    customFieldsMap.put("Ticket Triage Category", customFieldManager.getCustomFieldObjectsByName("Ticket Triage Category").first())
    customFieldsMap.put("Triage Category", customFieldManager.getCustomFieldObjectsByName("Triage Category").first())
    customFieldsMap.put("Triage Assignment", customFieldManager.getCustomFieldObjectsByName("Triage Assignment").first())
    customFieldsMap.put("Program", customFieldManager.getCustomFieldObjectsByName("Program").first())

    return customFieldsMap

}

def getConnection() {
    def delegator = (DelegatorInterface) ComponentAccessor.getComponent(DelegatorInterface)
    def helperName = delegator.getGroupHelperName("default")
    return ConnectionFactory.getConnection(helperName)
}

def updateIssue(issue) {
    def issueManager = ComponentAccessor.getIssueManager()
    def user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    issueManager.updateIssue(user, (MutableIssue) issue, EventDispatchOption.ISSUE_UPDATED, false)
}

def fromCommaSeparatedString(string) {
    Iterable<String> split = Splitter.on(",").omitEmptyStrings().trimResults().split(string)
    return Lists.newArrayList(split)
}

def removeSquareBracket(string) {
    if (StringUtils.isNotEmpty(string)) {
        return string.replaceAll("[\\[\\]]", "")
    }
    return string;
}