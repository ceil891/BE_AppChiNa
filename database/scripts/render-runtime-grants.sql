-- Run with psql on the NEW Render application database after migration V28.
-- Refuses an existing role; review existing grants instead of replacing them.
\set ON_ERROR_STOP on
BEGIN;
CREATE ROLE chinann_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO chinann_runtime', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO chinann_runtime;
GRANT SELECT ON general_settings,account_sessions,account_tokens,ai_conversations,ai_daily_usage,ai_global_usage,ai_runtime_settings,ai_turns,auth_request_limits,authorization_lock,course_lessons,courses,flashcard_reviews,foundation_lessons,foundation_progress,foundation_submissions,learning_events,learning_records,lesson_vocabulary,lessons,permissions,quiz_answer_requests,quiz_attempt_questions,quiz_attempts,quiz_questions,quiz_sets,role_permissions,roles,skill_attempts,skill_exercises,user_vocabulary,users,vocabulary TO chinann_runtime;
GRANT INSERT ON account_sessions,account_tokens,ai_conversations,ai_daily_usage,ai_global_usage,ai_runtime_settings,ai_turns,auth_request_limits,course_lessons,courses,flashcard_reviews,foundation_lessons,foundation_progress,foundation_submissions,learning_events,learning_records,lesson_vocabulary,lessons,quiz_answer_requests,quiz_attempt_questions,quiz_attempts,quiz_questions,quiz_sets,role_permissions,roles,skill_attempts,skill_exercises,user_vocabulary,users,vocabulary TO chinann_runtime;
GRANT UPDATE ON general_settings,account_sessions,ai_conversations,ai_daily_usage,ai_global_usage,ai_runtime_settings,ai_turns,auth_request_limits,courses,foundation_lessons,foundation_progress,learning_records,lessons,quiz_attempt_questions,quiz_attempts,quiz_sets,roles,skill_attempts,skill_exercises,user_vocabulary,users,vocabulary TO chinann_runtime;
GRANT DELETE ON account_tokens,ai_conversations,auth_request_limits,course_lessons,courses,lesson_vocabulary,lessons,quiz_questions,quiz_sets,role_permissions,roles,users,vocabulary TO chinann_runtime;
GRANT UPDATE(id) ON authorization_lock TO chinann_runtime;
GRANT UPDATE(token_hash) ON account_tokens TO chinann_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chinann_runtime;
ALTER ROLE chinann_runtime SET statement_timeout='15s';
ALTER ROLE chinann_runtime SET idle_in_transaction_session_timeout='30s';
COMMIT;
-- Interactive prompt avoids putting the password into this SQL file.
\password chinann_runtime
