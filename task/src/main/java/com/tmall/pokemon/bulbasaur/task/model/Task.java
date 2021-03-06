package com.tmall.pokemon.bulbasaur.task.model;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.tmall.pokemon.bulbasaur.core.CoreModule;
import com.tmall.pokemon.bulbasaur.core.Result;
import com.tmall.pokemon.bulbasaur.core.annotation.NeedDAOMeta;
import com.tmall.pokemon.bulbasaur.core.annotation.StateMeta;
import com.tmall.pokemon.bulbasaur.core.invoke.Invokable;
import com.tmall.pokemon.bulbasaur.core.invoke.InvokableFactory;
import com.tmall.pokemon.bulbasaur.core.model.Event;
import com.tmall.pokemon.bulbasaur.core.model.StateLike;
import com.tmall.pokemon.bulbasaur.persist.domain.JobDO;
import com.tmall.pokemon.bulbasaur.persist.domain.ParticipationDO;
import com.tmall.pokemon.bulbasaur.persist.domain.TaskDO;
import com.tmall.pokemon.bulbasaur.persist.domain.TaskDOExample;
import com.tmall.pokemon.bulbasaur.persist.mapper.JobDOMapper;
import com.tmall.pokemon.bulbasaur.persist.mapper.ParticipationDOMapper;
import com.tmall.pokemon.bulbasaur.persist.mapper.TaskDOMapper;
import com.tmall.pokemon.bulbasaur.schedule.process.JobHelper;
import com.tmall.pokemon.bulbasaur.task.constant.ParticipationConstant;
import com.tmall.pokemon.bulbasaur.task.constant.TaskConstant;
import com.tmall.pokemon.bulbasaur.task.constant.TaskStatusEnum;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson.JSON;

import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.ASSIGNMENT_HANDLER_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.CANDIDATE_USERS_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.COUNTERSIGNATURE_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.DAY_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.HOUR_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.IGNOREWEEKEND_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.MINUTE_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.OUTGOING_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.PERIOD_TAG;
import static com.tmall.pokemon.bulbasaur.core.constants.XmlTagConstants.TIMEOUT_HANDLER_TAG;

/**
 * ????????????
 * 1. ???prepare????????????????????????
 * 2. ????????????
 * 3. ??????????????? taskids ??????????????????
 * <p/>
 * User: yunche.ch - - - - (??? ??o??)???
 * Date: 13-11-6
 * Time: ??????4:56
 */
@StateMeta(t = "task")
@NeedDAOMeta(need = true)
public class Task extends Event {
    private final static Logger logger = LoggerFactory.getLogger(Task.class);

    private final static int MAX_USER = 100;
    //????????????????????????
    private String candidateUsers;
    //??????
    private Boolean countersignature;
    //???????????????
    private Invokable assignmentHandler;
    private Invokable timeoutHandler;
    //????????????
    private Map<String, Object> dAOMap;

    @Override
    public Result prepare(Map<String, Object> context) {
        Result result = new Result();
        logger.info("<========= Task preInvoke  start [task:" + this.getStateName() + "] =========>");

        Object userListObj = null;
        List<User> userList = Lists.newArrayList();

        //?????????????????????
        if (StringUtils.isNotBlank(candidateUsers)) {
            userListObj = candidateUsers;
        }

        // assignmentHandler ????????????????????????????????????????????? candidateUsers
        if (assignmentHandler != null) {
            // ????????????????????????invoke?????????????????????????????????????????????
            userListObj = assignmentHandler.invoke(context);
        }

        if (userListObj == null) {
            logger.warn("?????????????????????????????????????????????");
            result.setContinue(true);
            return result;
        }
        // ???????????????
        try {
            Map<String, String> userMap = Splitter.on(",").withKeyValueSeparator(":").split(userListObj.toString());

            for (Map.Entry<String, String> tmp : userMap.entrySet()) {
                User user = new User();
                user.setId(Long.valueOf(tmp.getKey()));
                user.setName(tmp.getValue());
                userList.add(user);
            }
        } catch (Exception e) {
            throw new RuntimeException("??????????????????user???userListObj = [" + userListObj + "]");
        }

        if (userList.size() > MAX_USER) {
            logger.warn(String.format("above %s  candidateUsers for task: %s", MAX_USER, this.getStateName()));
            throw new RuntimeException("above 100  candidateUsers for task:" + this.getStateName());
        }

        // ???????????????task
        TaskDOMapper taskDOMapper = (TaskDOMapper)dAOMap.get("taskDOMapper");
        JobDOMapper jobDOMapper = (JobDOMapper)dAOMap.get("jobDOMapper");

        List<TaskDO> tasks = new ArrayList<TaskDO>();
        TaskDO taskDO = new TaskDO();
        taskDO.setBizId(this.getBizId());
        taskDO.setDefinitionName(this.getDefinitionName());
        taskDO.setName(getStateName());
        taskDO.setStatus(TaskStatusEnum.STATUS_CREATED.getValue());
        taskDO.setGmtCreate(new Date());
        taskDO.setGmtModified(new Date());

        // ??????????????????????????????????????????
        if (context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_BIZ_INFO) != null) {
            TaskBizInfo taskBizInfo = (TaskBizInfo)context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_BIZ_INFO);
            taskDO.setBizInfo(JSON.toJSONString(taskBizInfo.getT()));
        }

        if (userList.size() == 1) {
            // ???????????????????????? ??????????????? ???????????????????????????
            taskDO.setType(TaskConstant.TASK_TYPE_SINGLE_USER);
            User user = userList.get(0);
            doWithCreator(taskDO, context);
            taskDO.setUserId(user.getId());
            taskDO.setUserName(user.getName());
            //insert DB??????????????????taskId
            taskDOMapper.insert(taskDO);
            tasks.add(taskDO);
        } else {

            if (countersignature != null && countersignature) {
                // ??????????????? ????????????????????????????????????
                for (User forkUser : userList) {
                    TaskDO forkTask = new TaskDO();
                    BeanUtils.copyProperties(taskDO, forkTask);
                    forkTask.setType(TaskConstant.TASK_TYPE_COUNTERSIGNATURE);
                    forkTask.setUserId(forkUser.getId());
                    forkTask.setUserName(forkUser.getName());
                    doWithCreator(forkTask, context);
                    //insert DB??????????????????taskId
                    taskDOMapper.insert(forkTask);
                    tasks.add(forkTask);
                }
            } else {
                ParticipationDOMapper participationDOMapper = (ParticipationDOMapper)dAOMap.get(
                    "participationDOMapper");

                // ???????????? ????????????????????????????????? participation
                taskDO.setType(TaskConstant.TASK_TYPE_MULTI_USER);
                doWithCreator(taskDO, context);
                taskDOMapper.insert(taskDO);
                tasks.add(taskDO);
                for (User partUser : userList) {
                    ParticipationDO participation = new ParticipationDO();

                    participation.setTaskId(taskDO.getId());
                    participation.setGmtCreate(new Date());
                    participation.setGmtModified(new Date());
                    // ???TODO
                    participation.setStatus(true);
                    participation.setType(ParticipationConstant.TYPE_ORI_USER);
                    participation.setUserId(partUser.getId());
                    participation.setUserName(partUser.getName());
                    participation.setDefinitionName(this.getDefinitionName());
                    participationDOMapper.insert(participation);
                }
            }

        } //else

        // ???????????? , ??????taskids
        StringBuilder taskIds = new StringBuilder();
        for (TaskDO mTask : tasks) {
            if (timeoutHandler != null) {
                addTaskTimeoutJob(mTask, context, jobDOMapper, taskDOMapper);
            }

            taskIds.append(mTask.getId()).append(",");
        }
        //?????????????????????taskids???????????????????????????????????????
        //??????????????????????????????????????????!
        context.put(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_TASK_IDS, taskIds.toString());

        logger.info("<========= taskPre execute end task:" + getStateName() + "] =========>");
        //??????pre-invokes
        super.prepare(context);
        result.setContinue(false);
        return result;
    }

    private void doWithCreator(TaskDO taskDO, Map<String, Object> context) {
        if (taskDO == null || context == null) {
            return;
        }

        if (context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_CREATOR_ID) != null) {
            try {
                // string or long or int
                taskDO.setCreatorId(
                    Long.valueOf(context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_CREATOR_ID).toString()));
            } catch (Exception e) {
                logger.error(String.format("????????????creatorId = %s ??????! ?????????????????? = %s",
                    context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_CREATOR_ID), context));
            }
        }

        if (context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_CREATOR_NAME) != null) {
            taskDO.setCreatorName(context.get(TaskConstant.BULBASAUR_INNER_CONTEXT_KEY_CREATOR_NAME).toString());
        }

    }

    @Override
    public Result execute(Map<String, Object> context) {

        TaskDOMapper taskDOMapper = (TaskDOMapper)dAOMap.get("taskDOMapper");
        TaskDOExample taskDOExample = new TaskDOExample();
        taskDOExample.createCriteria().andNameEqualTo(this.getStateName()).andBizIdEqualTo(this.getBizId());

        List<TaskDO> queryResultList = taskDOMapper.selectByExample(taskDOExample);

        Preconditions.checkArgument(queryResultList != null && !queryResultList.isEmpty(),
            String.format("????????????????????????bizId = [%s],name = [%s]", this.getBizId(), this.getStateName()));

        TaskDO validTaskDO = null;
        if (countersignature != null && countersignature) {
            //TODO ??????
        } else {

            for (TaskDO taskDO : queryResultList) {

                if (taskDO.getAssignUserId() != null) {
                    continue;
                }

                logger.warn(String.format("bizid = [%s] ,task id = [%s],task type = [%s]"
                    , taskDO.getBizId(), taskDO.getId(), taskDO.getType()));
                //???????????????????????????????????????
                validTaskDO = taskDO;
            }

        }

        if (StringUtils.isBlank(validTaskDO.getStatus())) {
            logger.warn("???????????????????????????????????????");
            Result result = new Result();
            result.setContinue(false);
            return result;
        }

        //FIXME ???????????????????????????

        //        if (StringUtils.isNotBlank(queryResult.getStatus()) && TaskConstant.STATUS_CREATED.equals
        // (queryResult.getStatus())) {
        //            logger.error("?????????????????????????????????????????????????????????????????????????????????bizId = [" + this.getBizId() + "],name = [" + this
        // .getStateName() + "]");
        //            Result result = new Result();
        //            result.setContinue(false);
        //            return result;
        //
        //        }
        //        String oldStatus = validTaskDO.getStatus();
        //        validTaskDO.setStatus(TaskConstant.STATUS_COMPLETED);
        //        queryResult.setEndTime(new Date());
        //        long duration = now.getTime() - queryResult.getGmtCreate().getTime();
        //        queryResult.setDuration(duration);
        //
        //
        //        // ?????????????????????task?????? ????????????
        //        int row = taskDAO.updateByStatusAndUser(queryResult, oldStatus);
        //        if (row == 0) {
        //            // ???????????? ??????????????????????????????
        //            throw new RuntimeException("CompleteTask???????????????????????????????????? - bizId: " + query.getBizId() + " | name
        // :" + query.getName());
        //        }

        return super.execute(context);

    }

    /**
     * ????????????????????????task????????? TaskConstant.STATUS_TIMEOUT_JOB
     *
     * @param mTask void
     * @since 2013-11-8 ??????09:10:37
     */
    private void addTaskTimeoutJob(TaskDO mTask, Map<String, Object> context, JobDOMapper jobDOMapper,
                                   TaskDOMapper taskDOMapper) {
        // timeout?????????job???
        JobDO jobDO = new JobDO();
        jobDO.setBizId(mTask.getBizId());
        jobDO.setStateName(mTask.getName());
        jobDO.setGmtCreate(new Date());
        jobDO.setGmtModified(new Date());
        jobDO.setEndTime(new Date());

        jobDO.setStatus("INIT");
        jobDO.setModNum(JobHelper.BKDRHash(mTask.getBizId()));
        jobDO.setEventType(TaskConstant.TASK_JOB_TYPE);
        jobDO.setTaskId(mTask.getId());
        jobDO.setOwnSign(CoreModule.getInstance().getOwnSign());
        // ????????????????????????invoke?????????????????????????????????????????????
        Object timeoutStrategy = timeoutHandler.invoke(context);
        calcTimeoutStrategy(timeoutStrategy, jobDO);
        //????????????
        TaskDO taskDO = new TaskDO();
        taskDO.setId(mTask.getId());
        String oldStatus = mTask.getStatus();
        taskDO.setStatus(TaskStatusEnum.STATUS_TIMEOUT_JOB.getValue());

        TaskDOExample taskDOExample = new TaskDOExample();
        taskDOExample.createCriteria().andStatusEqualTo(oldStatus);
        taskDOMapper.updateByExampleSelective(taskDO, taskDOExample);

        jobDOMapper.insert(jobDO);

    }

    private void calcTimeoutStrategy(Object timeoutStrategy, JobDO jobDO) {
        Preconditions.checkArgument(timeoutStrategy != null, "?????????????????????????????????");
        Preconditions.checkArgument(timeoutStrategy instanceof Map, "????????????????????????????????????");

        Map<String, Object> timeoutStrategyMap = (Map<String, Object>)timeoutStrategy;

        Preconditions.checkArgument(timeoutStrategyMap.get("outGoing") != null, "outGoing ????????????");
        Preconditions.checkArgument(timeoutStrategyMap.get("period") != null, "period???????????? ");
        // endTime  ??????
        //        if (timeoutStrategyMap.get("endTime") != null) {
        //            try {
        //                DateTime dateTime = new DateTime(timeoutStrategyMap.get("endTime"));
        //                jobDO.setEndTime(dateTime.toDate());
        //            } catch (Exception e) {
        //                throw new RuntimeException("????????????endtime????????????");
        //            }
        //        } else {
        //
        //            Preconditions.checkArgument(timeoutStrategyMap.get("period") != null, "endTime , period ??????????????? ");
        //            String meet = (String) timeoutStrategyMap.get("period");
        //
        //            // ?????? s , m , d
        //            DateTime dateTime = null;
        //            try {
        //                if (CharMatcher.anyOf(meet).matchesAllOf("s")) {
        //                    dateTime = new DateTime().plusSeconds(Integer.valueOf(CharMatcher.DIGIT.retainFrom
        // (meet)));
        //                } else if (CharMatcher.anyOf(meet).matchesAllOf("m")) {
        //                    dateTime = new DateTime().plusMinutes(Integer.valueOf(CharMatcher.DIGIT.retainFrom
        // (meet)));
        //                } else if (CharMatcher.anyOf(meet).matchesAllOf("d")) {
        //                    dateTime = new DateTime().plusDays(Integer.valueOf(CharMatcher.DIGIT.retainFrom(meet)));
        //                } else {
        //                    throw new RuntimeException("??????????????????????????????");
        //                }
        //            } catch (Exception e) {
        //                throw new RuntimeException("??????????????????????????????");
        //            }
        //
        //            jobDO.setEndTime(dateTime.toDate());
        //        }
        String meet = String.valueOf(timeoutStrategyMap.get(PERIOD_TAG));
        Preconditions.checkArgument(
            CharMatcher.anyOf(meet).matchesAllOf(MINUTE_TAG)
                | CharMatcher.anyOf(meet).matchesAllOf(HOUR_TAG)
                | CharMatcher.anyOf(meet).matchesAllOf(DAY_TAG), "period ???????????? minute or hour or day ");
        jobDO.setRepetition(meet);

        //????????????jumpTo???????????????????????????????????????????????????
        jobDO.setOutGoing(String.valueOf(timeoutStrategyMap.get(OUTGOING_TAG)));

        if (timeoutStrategyMap.get(IGNOREWEEKEND_TAG) != null) {
            Boolean ignoreWeekend = (Boolean)timeoutStrategyMap.get(IGNOREWEEKEND_TAG);
            jobDO.setIgnoreWeekend(ignoreWeekend);
        }

    }

    @Override
    public StateLike parse(Element elem) {
        super.parse(elem);

        if (elem.attributeValue(CANDIDATE_USERS_TAG) != null) {
            candidateUsers = elem.attributeValue(CANDIDATE_USERS_TAG);
        }
        //        if (elem.attributeValue("noAssigneeTo") != null)
        //            noAssigneeTo = elem.attributeValue("noAssigneeTo");
        if (elem.attributeValue(COUNTERSIGNATURE_TAG) != null) {
            countersignature = Boolean.valueOf(elem.attributeValue(COUNTERSIGNATURE_TAG));
        }

        Element ah = (Element)elem.selectSingleNode(ASSIGNMENT_HANDLER_TAG);
        if (ah != null) {
            List<Element> scList = ah.elements();
            if (scList != null) {
                try {
                    assignmentHandler = InvokableFactory.newInstance(scList.get(0).getName(), scList.get(0));
                } catch (RuntimeException re) {
                    logger.error(String.format("?????? %s ???????????????????????????%s , ???????????? %s"
                        , ASSIGNMENT_HANDLER_TAG
                        , scList.get(0).getName()
                        , re.toString()));
                    throw re;
                } catch (Throwable e) {
                    logger.error(String.format("?????? %s ???????????????????????????%s , ???????????? %s"
                        , ASSIGNMENT_HANDLER_TAG
                        , scList.get(0).getName()
                        , e.toString()));
                    throw new UndeclaredThrowableException(e,
                        "error happened when newInstance Invokable class:" + scList.get(0).getName());
                }
            }
        }

        Element to = (Element)elem.selectSingleNode(TIMEOUT_HANDLER_TAG);
        if (to != null) {
            List<Element> toList = to.elements();
            if (toList != null) {
                try {
                    timeoutHandler = InvokableFactory.newInstance(toList.get(0).getName(), toList.get(0));
                } catch (RuntimeException re) {
                    logger.error(String.format("?????? %s ?????????????????????%s , ???????????? %s"
                        , TIMEOUT_HANDLER_TAG
                        , toList.get(0).getName()
                        , re.toString()));
                    throw re;
                } catch (Throwable e) {
                    logger.error(String.format("?????? %s ?????????????????????%s , ???????????? %s"
                        , TIMEOUT_HANDLER_TAG
                        , toList.get(0).getName()
                        , e.toString()));
                    throw new UndeclaredThrowableException(e,
                        "error happened when newInstance Invokable class:" + toList.get(0).getName());
                }
            }
        }

        return this;
    }

    @Override
    public void setDAOMap(Map<String, ?> map) {
        this.dAOMap = (Map<String, Object>)map;
    }

}
