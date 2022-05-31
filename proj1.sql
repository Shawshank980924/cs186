-- Before running drop any existing views
DROP VIEW IF EXISTS q0;
DROP VIEW IF EXISTS q1i;
DROP VIEW IF EXISTS q1ii;
DROP VIEW IF EXISTS q1iii;
DROP VIEW IF EXISTS q1iv;
DROP VIEW IF EXISTS q2i;
DROP VIEW IF EXISTS q2ii;
DROP VIEW IF EXISTS q2iii;
DROP VIEW IF EXISTS q3i;
DROP VIEW IF EXISTS q3ii;
DROP VIEW IF EXISTS q3iii;
DROP VIEW IF EXISTS q4i;
DROP VIEW IF EXISTS q4ii;
DROP VIEW IF EXISTS q4iii;
DROP VIEW IF EXISTS q4iv;
DROP VIEW IF EXISTS q4v;

-- Question 0
CREATE VIEW q0(era)
AS
  -- SELECT 1 -- replace this line
  SELECT MAX(era)
  FROM pitching
;

-- Question 1i
CREATE VIEW q1i(namefirst, namelast, birthyear)
AS
  -- SELECT 1, 1, 1 -- replace this line
  SELECT namefirst,namelast,birthyear
  from people
  where weight>300
  
;

-- Question 1ii
CREATE VIEW q1ii(namefirst, namelast, birthyear)
AS
  -- SELECT 1, 1, 1 -- replace this line
  SELECT namefirst,namelast,birthyear
  from people
  where namefirst like "% %"
  ORDER BY namefirst,namelast
;

-- Question 1iii
CREATE VIEW q1iii(birthyear, avgheight, count)
AS
  -- SELECT 1, 1, 1 -- replace this line
  SELECT birthyear, avg(height), count(playerID)
  from people
  GROUP BY birthyear
  ORDER BY birthyear

;

-- Question 1iv
CREATE VIEW q1iv(birthyear, avgheight, count)
AS
  -- SELECT 1, 1, 1 -- replace this line
  SELECT birthyear, avg(height), count(playerID)
  from people
  GROUP BY birthyear
  HAVING avg(height)>70
  ORDER BY birthyear

;

-- Question 2i
CREATE VIEW q2i(namefirst, namelast, playerid, yearid)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT namefirst,namelast,h.playerID,yearid
  from HallofFame AS h
  JOIN people AS p
  ON (h.playerID=p.playerID)
  where inducted ="Y"
  ORDER BY yearid DESC,h.playerID ASC

;

-- Question 2ii
CREATE VIEW q2ii(namefirst, namelast, playerid, schoolid, yearid)
AS
  -- SELECT 1, 1, 1, 1, 1 -- replace this line
  SELECT namefirst,namelast,c.playerID,s.schoolid,q.yearid
  from q2i q
  JOIN CollegePlaying AS c
  ON (q.playerid=c.playerid)
  JOIN Schools as s
  ON (s.schoolid=c.schoolid)
  where schoolState="CA"
  ORDER BY yearid DESC,s.schoolid ASC,c.playerID ASC

;

-- Question 2iii
CREATE VIEW q2iii(playerid, namefirst, namelast, schoolid)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT q.playerid,namefirst,namelast,c.schoolid
  from q2i q
  LEFT OUTER JOIN collegeplaying c
  ON (c.playerid = q.playerid)
  ORDER BY q.playerid DESC,c.schoolid ASC

;

-- Question 3i
CREATE VIEW q3i(playerid, namefirst, namelast, yearid, slg)
AS
  -- SELECT 1, 1, 1, 1, 1 -- replace this line
  SELECT b.playerid,namefirst,namelast,yearid,
  1.0*(H-H2B-H3B-HR+2*H2B+3*H3B+4*HR)/AB AS slg
  from people p
  JOIN batting b
  on (p.playerid = b.playerid )
  where AB>50
  ORDER BY slg DESC
  LIMIT 10
;

-- Question 3ii
CREATE VIEW q3ii(playerid, namefirst, namelast, lslg)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT b.playerid,namefirst,namelast,
  1.0*sum((H-H2B-H3B-HR+2*H2B+3*H3B+4*HR))/sum(AB) AS lslg
  from people p
  JOIN batting b
  on (p.playerid = b.playerid )
  GROUP BY b.playerid
  HAVING sum(AB)>50
  ORDER BY lslg DESC,b.playerid
  LIMIT 10
;


-- Question 3iii
CREATE VIEW q3iii(namefirst, namelast, lslg)
AS
  -- SELECT 1, 1, 1 -- replace this line
  SELECT namefirst,namelast,
  1.0*sum((H-H2B-H3B-HR+2*H2B+3*H3B+4*HR))/sum(AB) AS lslg
  from people p
  JOIN batting b
  on (p.playerid = b.playerid )

  GROUP BY b.playerid
  HAVING sum(AB)>50 AND lslg>(SELECT 1.0*sum((H-H2B-H3B-HR+2*H2B+3*H3B+4*HR))/sum(AB)
  FROM batting b
  where b.playerid="mayswi01")
;

-- Question 4i
CREATE VIEW q4i(yearid, min, max, avg)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT yearid,min(salary),max(salary),avg(salary)
  from salaries
  GROUP BY yearid
  ORDER BY yearid

;


-- Helper table for 4ii
DROP TABLE IF EXISTS binids;
CREATE TABLE "binids"(
  "binid" INTEGER,
  "low" DOUBLE,
  "high" DOUBLE,
  PRIMARY KEY("binid")
  );
INSERT INTO binids VALUES 
(0,507500.0,507500.0+3249250.0), 
(1,507500.0+3249250.0,507500.0+2*3249250.0), 
(2,507500.0+2*3249250.0,507500.0+3*3249250.0), 
(3,507500.0+3*3249250.0,507500.0+4*3249250.0), 
(4,507500.0+4*3249250.0,507500.0+5*3249250.0), 
(5,507500.0+5*3249250.0,507500.0+6*3249250.0), 
(6,507500.0+6*3249250.0,507500.0+7*3249250.0), 
(7,507500.0+7*3249250.0,507500.0+8*3249250.0), 
(8,507500.0+8*3249250.0,507500.0+9*3249250.0), 
(9,507500.0+9*3249250.0,507500.0+10*3249250.0);
-- 3,249,250 per range
-- Question 4ii
CREATE VIEW q4ii(binid, low, high, count)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT binid,low,high,count(ID)
  FROM binids,salaries
  WHERE yearid = 2016 AND((binid<9 AND salary>=low AND salary <high) OR (binid=9 AND salary>=low AND salary <=high))
  GROUP BY binid
  ORDER BY binid
;

-- Question 4iii
CREATE VIEW q4iii(yearid, mindiff, maxdiff, avgdiff)
AS
  -- SELECT 1, 1, 1, 1 -- replace this line
  SELECT q.yearid,
  min-(SELECT min from q4i WHERE q4i.yearid+1=q.yearid),
  max-(SELECT max from q4i WHERE q4i.yearid+1=q.yearid),
  avg-(SELECT avg from q4i WHERE q4i.yearid+1=q.yearid)
  FROM q4i q
  WHERE q.yearid>(SELECT yearid from q4i LIMIT 1)
  ORDER BY yearid


;

-- Question 4iv
CREATE VIEW q4iv(playerid, namefirst, namelast, salary, yearid)
AS
  -- SELECT 1, 1, 1, 1, 1 -- replace this line
  SELECT s.playerid,namefirst,namelast,salary,yearid
  FROM salaries s
  JOIN people p
  ON(p.playerid = s.playerid)
  GROUP BY yearid
  HAVING max(salary)=salary AND yearid IN (2000,2001)

;
-- Question 4v
CREATE VIEW q4v(team, diffAvg) AS
  -- SELECT 1, 1 -- replace this line
  SELECT a.teamid,
  max(salary)-min(salary)
  FROM allstarfull a
  JOIN salaries s
  ON s.playerid=a.playerid and s.yearid = a.yearid
  WHERE a.yearid = 2016
  GROUP BY a.teamid

;

