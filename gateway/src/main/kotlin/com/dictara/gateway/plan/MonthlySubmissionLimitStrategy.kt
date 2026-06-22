package com.dictara.gateway.plan

import com.dictara.gateway.repository.SubmissionRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MonthlySubmissionLimitStrategy(
    private val limit: Int,
    private val submissionRepo: SubmissionRepository,
) : PlanStrategy {
    override fun enforce(userId: UUID) {
        val count = submissionRepo.countCurrentMonthSubmissions(userId)
        if (count >= limit) {
            val resetDate = YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(1)
            val resetStr = resetDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Monthly transcription limit reached ($limit/month). Resets on $resetStr.",
            )
        }
    }
}
