# Copyright 2014 Hewlett-Packard
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import abc

import six


@six.add_metaclass(abc.ABCMeta)
class MetricsRepository(object):
    @abc.abstractmethod
    def list_metrics(self, tenant_id, region, name, dimensions):
        pass

    @abc.abstractmethod
    def measurement_list(self, tenant_id, region, name, dimensions,
                         start_timestamp,
                         end_timestamp):
        pass

    @abc.abstractmethod
    def metrics_statistics(self, tenant_id, region, name, dimensions,
                           start_timestamp, end_timestamp, statistics, period):
        pass