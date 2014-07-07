/*
 * Copyright 2011-2014 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.hadoop.pig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.io.IOUtils;
import org.apache.pig.PigException;
import org.apache.pig.PigServer;
import org.apache.pig.backend.BackendException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.executionengine.ExecJob;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.JobCreationException;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.SchemaMergeException;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.parser.ParserException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Internal utility class executing Pig scripts and converting Pig exceptions to DataAccessExceptions.
 * 
 * Handles the various code changes on the API between Pig releases. 
 * 
 * @author Costin Leau
 */
abstract class PigUtils {

	static DataAccessException convert(PigException ex) {
		if (ex instanceof BackendException) {
			return new DataAccessResourceFailureException("Backend Pig exception", ex);
		}

		if (ex instanceof VisitorException || ex instanceof PlanException || ex instanceof SchemaMergeException) {
			return new InvalidDataAccessResourceUsageException("Plan failed", ex);
		}

		if (ex instanceof FrontendException) {
			if (ex instanceof JobCreationException) {
				return new InvalidDataAccessResourceUsageException("Map Reduce error", ex);
			}

			// work-around to let compilation on CDH3
			if (ex instanceof ParserException) {
				return new InvalidDataAccessResourceUsageException("Syntax error", ex);
			}
		}

		return new NonTransientDataAccessResourceException("Unknown Pig error", ex);
	}

	static DataAccessException convert(IOException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof PigException) {
			return convert((PigException) ex);
		}

		return new NonTransientDataAccessResourceException("Unknown Pig error", ex);
	}

	static List<ExecJob> run(PigServer pig, Iterable<PigScript> scripts) throws ExecException, IOException {
		Assert.notNull(scripts, "at least one script is required");

		if (!pig.isBatchOn()) {
			pig.setBatchOn();
		}

		List<ExecJob> jobs = new ArrayList<ExecJob>();

		pig.getPigContext().connect();

		InputStream in = null;
		try {
			for (PigScript script : scripts) {
				try {
					in = script.getResource().getInputStream();
				} catch (IOException ex) {
					throw new IllegalArgumentException("Cannot open script [" + script.getResource() + "]", ex);
				}
				pig.registerScript(in, script.getArguments());
				jobs.addAll(pig.executeBatch());
			}
		} finally {
			IOUtils.closeStream(in);
		}
		return jobs;
	}

	static List<ExecJob> runWithConversion(PigServer pig, Iterable<PigScript> scripts, boolean closePig)
			throws DataAccessException {
		try {
			return run(pig, scripts);
		} catch (ExecException ex) {
			throw convert(ex);
		} catch (IOException ex) {
			throw convert(ex);
		} finally {
			if (closePig) {
				pig.shutdown();
			}
		}
	}
}