package com.tmall.pokemon.bulbasaur.schedule.process;

import com.tmall.pokemon.bulbasaur.core.CoreModule;
import com.tmall.pokemon.bulbasaur.persist.constant.StateConstant;
import com.tmall.pokemon.bulbasaur.persist.domain.JobDO;
import com.tmall.pokemon.bulbasaur.persist.domain.JobDOExample;
import com.tmall.pokemon.bulbasaur.persist.domain.StateDO;
import com.tmall.pokemon.bulbasaur.persist.domain.StateDOExample;
import com.tmall.pokemon.bulbasaur.persist.mapper.JobDOMapper;
import com.tmall.pokemon.bulbasaur.persist.mapper.StateDOMapper;
import com.tmall.pokemon.bulbasaur.schedule.ScheduleMachineFactory;
import com.tmall.pokemon.bulbasaur.schedule.ScheduleModule;
import com.tmall.pokemon.bulbasaur.schedule.job.FailedRetryJob;
import com.tmall.pokemon.bulbasaur.schedule.job.Job;
import com.tmall.pokemon.bulbasaur.schedule.job.JobConstant;
import com.tmall.pokemon.bulbasaur.schedule.job.TimeOutJob;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class BulbasaurJobProcessor extends AbstractBulbasaurProcessor<JobDOExample, JobDO> implements org.quartz.Job {
    private static Logger logger = LoggerFactory.getLogger(BulbasaurJobProcessor.class);
    private static final int pageSize = 200;

    @Autowired
    ScheduleMachineFactory scheduleMachineFactory;
    @Autowired
    JobDOMapper jobDOMapper;
    @Autowired
    JobHelper jobHelper;
    @Autowired
    StateDOMapper stateDOMapper;
    @Autowired
    BulbasaurExecutorHelper bulbasaurExecutorHelper;

    @Override
    protected void shoot(List<JobDO> list) {

        boolean deleteOverdueJob = ScheduleModule.getInstance().getDeleteOverdueJob();

        try {
            for (Object beef : list) {
                if (beef instanceof JobDO) {
                    JobDO jobDO = (JobDO)beef;

                    // ?????????job???state???????????????complete
                    StateDOExample stateDOExample = new StateDOExample();
                    stateDOExample.setOrderByClause("id asc");
                    stateDOExample.createCriteria().andBizIdEqualTo(jobDO.getBizId()).andStateNameEqualTo(
                        jobDO.getStateName()).andOwnSignEqualTo(CoreModule.getInstance().getOwnSign());
                    List<StateDO> stateDOList = stateDOMapper.selectByExample(stateDOExample);

                    StateDO stateDO = stateDOList != null && !stateDOList.isEmpty() ? stateDOList.get(0) : null;
                    if (stateDO == null || StateConstant.STATE_COMPLETE.equals(stateDO.getStatus())) {
                        jobDOMapper.deleteByPrimaryKey(jobDO.getId());
                        continue;
                    }

                    if (StringUtils.isNotBlank(jobDO.getBizId())) {

                        // ???????????????????????????
                        long remaining = JobHelper.getRemainingTime(jobDO);

                        if (remaining <= 0) {
                            long start = System.currentTimeMillis();

                            Job job;

                            if (JobConstant.JOB_ENVENT_TYPE_FAILEDRETRY.equals(jobDO.getEventType())) {
                                job = new FailedRetryJob(jobDO, scheduleMachineFactory);
                            } else if (JobConstant.JOB_ENVENT_TYPE_TIMEOUT.equals(jobDO.getEventType())) {
                                job = new TimeOutJob(jobDO, scheduleMachineFactory);
                            } else if (JobConstant.JOB_ENVENT_TYPE_TIMER.equals(jobDO.getEventType())) {
                                job = new TimeOutJob(jobDO, scheduleMachineFactory);
                            } else {
                                logger.error("?????????JOB????????????????????????job :" + jobDO.toString());
                                throw new RuntimeException("?????????JOB????????????????????????job :" + jobDO.toString());
                            }

                            try {
                                job.doJob();
                            } catch (Exception e) {
                                logger.error(String
                                    .format("??????[%s]?????????[%s]????????????:[%s]", jobDO.getBizId(), jobDO.getStateName(),
                                        e.getMessage()));
                                logger.error(String.format("?????????????????? \n %s", ExceptionUtils.getStackTrace(e)));
                            } finally {

                                if (jobDO.getRepeatTimes() == null || jobDO.getRepeatTimes() < 1) {

                                    if (deleteOverdueJob) {
                                        // ????????????
                                        logger.error(String
                                            .format("bulbasaur job id = %s , bizId = %s , ??????????????????????????????,?????????.....",
                                                jobDO.getId(), jobDO.getBizId()));

                                        jobDOMapper.deleteByPrimaryKey(jobDO.getId());

                                    } else {
                                        // ?????????????????????????????????DONE
                                        Date now = new Date();
                                        jobDO.setGmtModified(now);
                                        jobDO.setStatus(JobConstant.JOB_STATUS_DONE);
                                        jobDOMapper.updateByPrimaryKeySelective(jobDO);
                                    }
                                } else {
                                    Date now = new Date();
                                    jobDO.setGmtModified(now);
                                    jobDO.setEndTime(now);
                                    jobDO.setStatus(JobConstant.JOB_STATUS_RUNNING);

                                    /**
                                     * repeatTimes ??????????????????????????????0??????????????????????????????????????????????????? repetition
                                     */
                                    jobDO.setRepeatTimes(jobDO.getRepeatTimes() - 1);
                                    if (StringUtils.isNotBlank(jobDO.getDealStrategy())) {
                                        if (jobDO.getRepeatTimes() != 0) {
                                            String[] repeatArray = jobDO.getDealStrategy().split("\\s");
                                            jobDO.setRepetition(JobHelper.transformRepeatStr(
                                                repeatArray[repeatArray.length - jobDO.getRepeatTimes().intValue()]));
                                        } else {
                                            jobDO.setRepetition("0");
                                        }
                                    }

                                    jobDOMapper.updateByPrimaryKeySelective(jobDO);
                                }
                            }
                            logger.info("??????????????????????????????:" + (System.currentTimeMillis() - start) / 1000 + " s");
                        }
                    }

                }
            }
        } catch (Exception e) {
            logger.error(String.format("bulbasaur??????????????????????????????: \n [%s]", ExceptionUtils.getStackTrace(e)));
        }
    }

    @Override
    public List<JobDO> query(int pageNo, JobDOExample example) {
        example.setOffset(PageSizeHelper.calcOffset(pageNo, querySupportPageSize()));
        return jobDOMapper.selectByExample(example);
    }

    @Override
    protected int querySupportPageSize() {
        return PAGE_SIZE;
    }

    @Override
    public int queryTotalCount(JobDOExample example) {
        return jobDOMapper.countByExample(example);
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDOExample jobDOExample = new JobDOExample();
        jobDOExample.setLimit(querySupportPageSize());

        JobDOExample.Criteria criteria = jobDOExample.createCriteria();
        /* ?????????DONE ???job*/
        criteria.andOwnSignEqualTo(CoreModule.getInstance().getOwnSign()).andStatusNotEqualTo(
            JobConstant.JOB_STATUS_DONE);

        try {
            handle(jobDOExample);
        } catch (Exception e) {
            logger.error("??????????????????????????????????????????e???" + e.getMessage());
        }

    }

}
